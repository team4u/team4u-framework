package com.team4u.framework.lease.spi;

public interface LeasePublisher {

    SubmitResult submit(SubmitCommand command);
}
