package com.team4u.framework.lease.spi;

public interface LeaseAdminService {

    AdminResult complete(AdminCompletionCommand command);

    AdminResult reschedule(RescheduleCommand command);

    AdminResult retry(RetryCommand command);

    AdminResult update(UpdateCommand command);

    AdminResult updateAndReschedule(UpdateCommand command);
}
