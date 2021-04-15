/*
 * Copyright (c) 2021 Snowflake Computing Inc. All rights reserved.
 */

package net.snowflake.ingest.utils;

/** Ingest SDK internal error codes */
public enum ErrorCode {
  INTERNAL_ERROR("0001");

  public static final String errorMessageResource = "net.snowflake.ingest.ingest_error_messages";

  /** Snowflake internal message associated to the error. */
  private final String messageCode;

  /**
   * Construct a new error code specification given Snowflake internal error code.
   *
   * @param messageCode Snowflake internal error code
   */
  ErrorCode(String messageCode) {
    this.messageCode = messageCode;
  }

  public String getMessageCode() {
    return messageCode;
  }

  @Override
  public String toString() {
    return "ErrorCode{" + "name=" + this.name() + ", messageCode=" + messageCode + "}";
  }
}
