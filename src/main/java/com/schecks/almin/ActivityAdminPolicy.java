package com.schecks.almin;

/** The activity-view policy sent to an administrator's client. */
public record ActivityAdminPolicy(boolean includeAdmins, boolean temporary, boolean configured) {}
