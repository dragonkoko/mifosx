/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.mifosplatform.infrastructure.hooks.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;

import org.joda.time.LocalDate;
import org.mifosplatform.infrastructure.core.domain.JdbcSupport;
import org.mifosplatform.infrastructure.core.service.RoutingDataSource;
import org.mifosplatform.infrastructure.hooks.data.Event;
import org.mifosplatform.infrastructure.hooks.data.EventResultSetExtractor;
import org.mifosplatform.infrastructure.hooks.data.Field;
import org.mifosplatform.infrastructure.hooks.data.Grouping;
import org.mifosplatform.infrastructure.hooks.data.HookData;
import org.mifosplatform.infrastructure.hooks.data.HookTemplateData;
import org.mifosplatform.infrastructure.hooks.exception.HookNotFoundException;
import org.mifosplatform.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

@Service
public class HookReadPlatformServiceImpl implements HookReadPlatformService {

	private final JdbcTemplate jdbcTemplate;
	private final PlatformSecurityContext context;

	@Autowired
	public HookReadPlatformServiceImpl(final PlatformSecurityContext context,
			final RoutingDataSource dataSource) {
		this.context = context;
		this.jdbcTemplate = new JdbcTemplate(dataSource);
	}

	@Override
	public Collection<HookData> retrieveAllHooks() {
		this.context.authenticatedUser();
		final HookMapper rm = new HookMapper(this.jdbcTemplate);
		final String sql = "select " + rm.schema() + " order by h.name";

		return this.jdbcTemplate.query(sql, rm, new Object[] {});
	}

	@Override
	public HookData retrieveHook(final Long hookId) {
		try {
			this.context.authenticatedUser();
			final HookMapper rm = new HookMapper(this.jdbcTemplate);
			final String sql = "select " + rm.schema() + " where h.id = ?";

			return this.jdbcTemplate.queryForObject(sql, rm,
					new Object[] { hookId });
		} catch (final EmptyResultDataAccessException e) {
			throw new HookNotFoundException(hookId);
		}

	}

	@Override
	public HookData retrieveNewHookDetails(final String templateName) {

		this.context.authenticatedUser();
		final TemplateMapper rm = new TemplateMapper(this.jdbcTemplate);
		final String sql;
		List<HookTemplateData> templateData;

		if (templateName == null) {
			sql = "select " + rm.schema() + " order by s.name";
			templateData = this.jdbcTemplate.query(sql, rm, new Object[] {});
		} else {
			sql = "select " + rm.schema() + " where s.name = ? order by s.name";
			templateData = this.jdbcTemplate.query(sql, rm,
					new Object[] { templateName });
		}

		final List<Grouping> events = getTemplateForEvents();

		return HookData.template(templateData, events);
	}

	private List<Grouping> getTemplateForEvents() {
		final String sql = "select p.grouping, p.entity_name, p.action_name from m_permission p "
				+ " where p.action_name NOT LIKE '%CHECKER%' AND p.action_name NOT LIKE '%READ%' "
				+ " order by p.grouping, p.entity_name ";
		final EventResultSetExtractor extractor = new EventResultSetExtractor();
		return this.jdbcTemplate.query(sql, extractor);
	}

	private static final class HookMapper implements RowMapper<HookData> {

		private final JdbcTemplate jdbcTemplate;

		public HookMapper(final JdbcTemplate jdbcTemplate) {
			this.jdbcTemplate = jdbcTemplate;
		}

		public String schema() {
			return " h.id, s.name as name, h.name as display_name, h.is_active, h.created_date, h.lastmodified_date "
					+ " from m_hook h inner join m_hook_templates s on h.template_id = s.id ";
		}

		@Override
		public HookData mapRow(final ResultSet rs,
				@SuppressWarnings("unused") final int rowNum)
				throws SQLException {

			final Long id = rs.getLong("id");
			final String name = rs.getString("name");
			final String displayname = rs.getString("display_name");
			final boolean isActive = rs.getBoolean("is_active");
			final LocalDate createdAt = JdbcSupport.getLocalDate(rs,
					"created_date");
			final LocalDate updatedAt = JdbcSupport.getLocalDate(rs,
					"lastmodified_date");
			final List<Event> registeredEvents = retrieveEvents(id);
			final List<Field> config = retrieveConfig(id);

			return HookData.instance(id, name, displayname, isActive,
					createdAt, updatedAt, registeredEvents, config);
		}

		private List<Event> retrieveEvents(final Long hookId) {

			final HookEventMapper rm = new HookEventMapper();
			final String sql = "select " + rm.schema() + " where h.id= ?";

			return this.jdbcTemplate.query(sql, rm, new Object[] { hookId });
		}

		private List<Field> retrieveConfig(final Long hookId) {

			final HookConfigMapper rm = new HookConfigMapper();
			final String sql = "select " + rm.schema()
					+ " where h.id= ? order by hc.field_name";

			final List<Field> fields = this.jdbcTemplate.query(sql, rm,
					new Object[] { hookId });

			return fields;
		}
	}

	private static final class HookEventMapper implements RowMapper<Event> {

		public String schema() {
			return " re.action_name, re.entity_name from m_hook h inner join m_hook_registered_events re on h.id = re.hook_id ";
		}

		@Override
		public Event mapRow(final ResultSet rs,
				@SuppressWarnings("unused") final int rowNum)
				throws SQLException {
			final String actionName = rs.getString("action_name");
			final String entityName = rs.getString("entity_name");
			return Event.instance(actionName, entityName);
		}
	}

	private static final class HookConfigMapper implements RowMapper<Field> {

		public String schema() {
			return " hc.field_name, hc.field_value from m_hook h inner join m_hook_configuration hc on h.id = hc.hook_id ";
		}

		@Override
		public Field mapRow(final ResultSet rs,
				@SuppressWarnings("unused") final int rowNum)
				throws SQLException {
			final String fieldName = rs.getString("field_name");
			final String fieldValue = rs.getString("field_value");
			return Field.fromConfig(fieldName, fieldValue);
		}
	}

	private static final class TemplateMapper implements
			RowMapper<HookTemplateData> {

		private final JdbcTemplate jdbcTemplate;

		public TemplateMapper(final JdbcTemplate jdbcTemplate) {
			this.jdbcTemplate = jdbcTemplate;
		}

		public String schema() {
			return " s.id, s.name from m_hook_templates s ";
		}

		@Override
		public HookTemplateData mapRow(final ResultSet rs,
				@SuppressWarnings("unused") final int rowNum)
				throws SQLException {

			final Long id = rs.getLong("id");
			final String name = rs.getString("name");
			final List<Field> schema = retrieveSchema(id);

			return HookTemplateData.instance(id, name, schema);
		}

		private List<Field> retrieveSchema(final Long templateId) {

			final TemplateSchemaMapper rm = new TemplateSchemaMapper();
			final String sql = "select " + rm.schema()
					+ " where s.id= ? order by hs.field_name ";

			final List<Field> fields = this.jdbcTemplate.query(sql, rm,
					new Object[] { templateId });

			return fields;
		}
	}

	private static final class TemplateSchemaMapper implements RowMapper<Field> {

		public String schema() {
			return " hs.field_type, hs.field_name, hs.placeholder, hs.optional from m_hook_templates s "
					+ " inner join m_hook_schema hs on s.id = hs.hook_template_id ";
		}

		@Override
		public Field mapRow(final ResultSet rs,
				@SuppressWarnings("unused") final int rowNum)
				throws SQLException {
			final String fieldName = rs.getString("field_name");
			final String fieldType = rs.getString("field_type");
			final Boolean optional = rs.getBoolean("optional");
			final String placeholder = rs.getString("placeholder");
			return Field
					.fromSchema(fieldType, fieldName, optional, placeholder);
		}
	}

}