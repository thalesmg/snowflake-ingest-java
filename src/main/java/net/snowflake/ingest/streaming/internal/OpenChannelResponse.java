/*
 * Copyright (c) 2021 Snowflake Computing Inc. All rights reserved.
 */

package net.snowflake.ingest.streaming.internal;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Response to the OpenChannelRequest */
class OpenChannelResponse {
  private Long statusCode;
  private String message;
  private String dbName;
  private String schemaName;
  private String tableName;
  private String channelName;
  private Long clientSequencer;
  private Long rowSequencer;
  private String offsetToken;
  private List<ColumnMetadata> tableColumns;

  @JsonProperty("status_code")
  void setStatusCode(Long statusCode) {
    this.statusCode = statusCode;
  }

  Long getStatusCode() {
    return this.statusCode;
  }

  @JsonProperty("message")
  void setMessage(String message) {
    this.message = message;
  }

  String getMessage() {
    return this.message;
  }

  @JsonProperty("database")
  void setDBName(String dbName) {
    this.dbName = dbName;
  }

  String getDBName() {
    return this.dbName;
  }

  @JsonProperty("schema")
  void setSchemaName(String schemaName) {
    this.schemaName = schemaName;
  }

  String getSchemaName() {
    return this.schemaName;
  }

  @JsonProperty("table")
  void setTableName(String tableName) {
    this.tableName = tableName;
  }

  String getTableName() {
    return this.tableName;
  }

  @JsonProperty("channel")
  void setChannelName(String channelName) {
    this.channelName = channelName;
  }

  String getChannelName() {
    return this.channelName;
  }

  @JsonProperty("client_sequencer")
  void setClientSequencer(Long clientSequencer) {
    this.clientSequencer = clientSequencer;
  }

  Long getClientSequencer() {
    return this.clientSequencer;
  }

  @JsonProperty("row_sequencer")
  void setRowSequencer(Long rowSequencer) {
    this.rowSequencer = rowSequencer;
  }

  Long getRowSequencer() {
    return this.rowSequencer;
  }

  @JsonProperty("offset_token")
  void setOffsetToken(String offsetToken) {
    this.offsetToken = offsetToken;
  }

  String getOffsetToken() {
    return this.offsetToken;
  }

  @JsonProperty("table_columns")
  void setTableColumns(List<ColumnMetadata> tableColumns) {
    this.tableColumns = tableColumns;
  }

  List<ColumnMetadata> getTableColumns() {
    return this.tableColumns;
  }
}
