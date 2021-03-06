/*
 * Copyright (C) 2014-2016 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied.
 */

package gobblin.converter.initializer;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.gson.Gson;

import gobblin.configuration.State;
import gobblin.converter.jdbc.AvroToJdbcEntryConverter;
import gobblin.converter.jdbc.JdbcType;
import gobblin.publisher.JdbcPublisher;
import gobblin.source.workunit.WorkUnit;
import gobblin.util.ForkOperatorUtils;
import gobblin.util.jdbc.DataSourceBuilder;
import gobblin.writer.commands.JdbcWriterCommands;
import gobblin.writer.commands.JdbcWriterCommandsFactory;

/**
 * Initialize for AvroToJdbcEntryConverter. ConverterInitializer is being invoked at driver which means
 * it will only invoked once per converter. This is to remove any duplication work among task, and
 * any initialization that is same per task can be put in here.
 */
public class AvroToJdbcEntryConverterInitializer implements ConverterInitializer {
  private static final Logger LOG = LoggerFactory.getLogger(AvroToJdbcEntryConverterInitializer.class);

  private final State state;
  private final Collection<WorkUnit> workUnits;
  private final JdbcWriterCommandsFactory jdbcWriterCommandsFactory;
  private final int branches;
  private final int branchId;

  public AvroToJdbcEntryConverterInitializer(State state, Collection<WorkUnit> workUnits) {
    this(state, workUnits, new JdbcWriterCommandsFactory(), 1, 0);
  }

  public AvroToJdbcEntryConverterInitializer(State state, Collection<WorkUnit> workUnits,
                                             JdbcWriterCommandsFactory jdbcWriterCommandsFactory,
                                             int branches, int branchId) {
    this.state = state;
    this.workUnits = workUnits;
    this.jdbcWriterCommandsFactory = jdbcWriterCommandsFactory;
    this.branches = branches;
    this.branchId = branchId;
  }

  /**
   * AvroToJdbcEntryConverter list of date columns existing in the table. As we don't want each converter
   * making a connection against database to get the same information. Here, ConverterInitializer will
   * retrieve it and store it into WorkUnit so that AvroToJdbcEntryConverter will use it later.
   *
   * {@inheritDoc}
   * @see gobblin.initializer.Initializer#initialize()
   */
  @Override
  public void initialize() {
    String table = Preconditions.checkNotNull(state.getProp(ForkOperatorUtils.getPropertyNameForBranch(
                                                            JdbcPublisher.JDBC_PUBLISHER_FINAL_TABLE_NAME,
                                                            branches,
                                                            branchId)));
    try (Connection conn = createConnection()) {
      JdbcWriterCommands commands = jdbcWriterCommandsFactory.newInstance(state, conn);
      Map<String, JdbcType> dateColumnMapping = commands.retrieveDateColumns(table);
      LOG.info("Date column mapping: " + dateColumnMapping);

      final String dateFieldsKey = ForkOperatorUtils.getPropertyNameForBranch(AvroToJdbcEntryConverter.CONVERTER_AVRO_JDBC_DATE_FIELDS,
                                                                              branches,
                                                                              branchId);
      for (WorkUnit wu : workUnits) {
        wu.setProp(dateFieldsKey, new Gson().toJson(dateColumnMapping));
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void close() { }

  @VisibleForTesting
  public Connection createConnection() throws SQLException {
    DataSource dataSource = DataSourceBuilder.builder()
                                             .url(state.getProp(JdbcPublisher.JDBC_PUBLISHER_URL))
                                             .driver(state.getProp(JdbcPublisher.JDBC_PUBLISHER_DRIVER))
                                             .userName(state.getProp(JdbcPublisher.JDBC_PUBLISHER_USERNAME))
                                             .passWord(state.getProp(JdbcPublisher.JDBC_PUBLISHER_PASSWORD))
                                             .cryptoKeyLocation(state.getProp(JdbcPublisher.JDBC_PUBLISHER_ENCRYPTION_KEY_LOC))
                                             .maxActiveConnections(1)
                                             .maxIdleConnections(1)
                                             .state(state)
                                             .build();

    return dataSource.getConnection();
  }

}
