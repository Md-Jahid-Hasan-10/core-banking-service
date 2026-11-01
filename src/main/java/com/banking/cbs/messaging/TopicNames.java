package com.banking.cbs.messaging;

public final class TopicNames {

    private TopicNames() {}

    public static final String CBS_COMMANDS = "cbs.commands";
    public static final String CBS_EVENTS = "cbs.events";

    /** A message that failed all its retries is parked on "<topic>.DLT". */
    public static final String DLT_SUFFIX = ".DLT";
}