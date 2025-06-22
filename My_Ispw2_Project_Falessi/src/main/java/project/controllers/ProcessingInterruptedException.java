package project.controllers;

public class ProcessingInterruptedException extends RuntimeException {
    public ProcessingInterruptedException(String message, Throwable cause) {
        super(message, cause);
    }
}
