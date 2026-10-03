package com.dongran.work.exception;

public class ModelIncompleteException extends RuntimeException {
  private final String reason;

  public ModelIncompleteException(String message) {
    this(message, "incomplete");
  }

  public ModelIncompleteException(String message, String reason) {
    super(message);
    this.reason = reason;
  }

  public String reason() {
    return reason;
  }
}
