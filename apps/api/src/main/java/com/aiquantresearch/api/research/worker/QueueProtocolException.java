package com.aiquantresearch.api.research.worker;

public class QueueProtocolException extends RuntimeException {

    public QueueProtocolException(String message) {
        super(message);
    }

    public QueueProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
