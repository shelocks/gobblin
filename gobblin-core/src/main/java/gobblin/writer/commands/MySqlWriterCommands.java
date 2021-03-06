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

package gobblin.writer.commands;

import gobblin.configuration.State;
import gobblin.converter.jdbc.JdbcType;
import gobblin.converter.jdbc.JdbcEntryData;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableMap;

/**
 * The implementation of JdbcWriterCommands for MySQL.
 */
public class MySqlWriterCommands implements JdbcWriterCommands {
  private static final Logger LOG = LoggerFactory.getLogger(MySqlWriterCommands.class);

  private static final String CREATE_TABLE_SQL_FORMAT = "CREATE TABLE %s SELECT * FROM %s WHERE 1=2";
  private static final String SELECT_SQL_FORMAT = "SELECT COUNT(*) FROM %s";
  private static final String TRUNCATE_TABLE_FORMAT = "TRUNCATE TABLE %s";
  private static final String DROP_TABLE_SQL_FORMAT = "DROP TABLE %s";
  private static final String INFORMATION_SCHEMA_SELECT_SQL_PSTMT
                              = "SELECT column_name, column_type FROM information_schema.columns where table_name = ?";
  private static final String COPY_INSERT_STATEMENT_FORMAT = "INSERT INTO %s.%s SELECT * FROM %s.%s";
  private static final String DELETE_STATEMENT_FORMAT = "DELETE FROM %s";

  private final JdbcBufferedInserter jdbcBufferedWriter;
  private final Connection conn;

  public MySqlWriterCommands(State state, Connection conn) {
    this.conn = conn;
    this.jdbcBufferedWriter = new MySqlBufferedInserter(state, conn);
  }

  @Override
  public void insert(String databaseName, String table, JdbcEntryData jdbcEntryData) throws SQLException {
    jdbcBufferedWriter.insert(databaseName, table, jdbcEntryData);
  }

  @Override
  public void flush() throws SQLException {
    jdbcBufferedWriter.flush();
  }

  @Override
  public void createTableStructure(String fromStructure, String targetTableName) throws SQLException {
    String sql = String.format(CREATE_TABLE_SQL_FORMAT, targetTableName, fromStructure);
    execute(sql);
  }

  @Override
  public boolean isEmpty(String table) throws SQLException {
    String sql = String.format(SELECT_SQL_FORMAT, table);
    try (PreparedStatement pstmt = conn.prepareStatement(sql);
         ResultSet resultSet = pstmt.executeQuery();) {
      if(!resultSet.first()) {
        throw new RuntimeException("Should have received at least one row from SQL " + pstmt);
      }
      return 0 == resultSet.getInt(1);
    }
  }

  @Override
  public void truncate(String table) throws SQLException {
    String sql = String.format(TRUNCATE_TABLE_FORMAT, table);
    execute(sql);
  }

  @Override
  public void deleteAll(String table) throws SQLException {
    String deleteSql = String.format(DELETE_STATEMENT_FORMAT, table);
    execute(deleteSql);
  }

  @Override
  public void drop(String table) throws SQLException {
    LOG.info("Dropping table " + table);
    String sql = String.format(DROP_TABLE_SQL_FORMAT, table);
    execute(sql);
  }

  /**
   * https://dev.mysql.com/doc/connector-j/en/connector-j-reference-type-conversions.html
   * {@inheritDoc}
   * @see gobblin.writer.commands.JdbcWriterCommands#retrieveDateColumns(java.sql.Connection, java.lang.String)
   */
  @Override
  public Map<String, JdbcType> retrieveDateColumns(String table) throws SQLException {
    Map<String, JdbcType> targetDataTypes = ImmutableMap.<String, JdbcType>builder()
        .put("DATE", JdbcType.DATE)
        .put("DATETIME", JdbcType.TIME)
        .put("TIME", JdbcType.TIME)
        .put("TIMESTAMP", JdbcType.TIMESTAMP)
        .build();

    ImmutableMap.Builder<String, JdbcType> dateColumnsBuilder = ImmutableMap.builder();
    try (PreparedStatement pstmt = conn.prepareStatement(INFORMATION_SCHEMA_SELECT_SQL_PSTMT)) {
      pstmt.setString(1, table);
      LOG.info("Retrieving column type information from SQL: " + pstmt);
      try (ResultSet rs = pstmt.executeQuery()) {
        if (!rs.first()) {
          throw new IllegalArgumentException("No result from information_schema.columns");
        }
        do {
          String type = rs.getString("column_type").toUpperCase();
          JdbcType convertedType = targetDataTypes.get(type);
          if (convertedType != null) {
            dateColumnsBuilder.put(rs.getString("column_name"), convertedType);
          }
        } while (rs.next());
      }
    }
    return dateColumnsBuilder.build();
  }



  @Override
  public void copyTable(String databaseName, String from, String to) throws SQLException {
    String sql = String.format(COPY_INSERT_STATEMENT_FORMAT, databaseName, to, databaseName, from);
    execute(sql);
  }

  private void execute(String sql) throws SQLException {
    LOG.info("Executing SQL " + sql);
    try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
      pstmt.execute();
    }
  }

  @Override
  public String toString() {
    return String.format("MySqlWriterCommands [bufferedWriter=%s]", jdbcBufferedWriter);
  }
}
