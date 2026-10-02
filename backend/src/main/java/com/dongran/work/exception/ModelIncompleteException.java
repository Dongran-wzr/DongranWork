package com.dongran.work.exception;

public class ModelIncompleteException extends RuntimeException {
  public ModelIncompleteException(String reason) {
    super(reason);
  }
}
