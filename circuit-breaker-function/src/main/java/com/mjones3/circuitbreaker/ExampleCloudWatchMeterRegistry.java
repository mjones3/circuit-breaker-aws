package com.mjones3.circuitbreaker;

import io.micrometer.cloudwatch2.CloudWatchConfig;
import io.micrometer.cloudwatch2.CloudWatchMeterRegistry;
import io.micrometer.core.instrument.Clock;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;

// a tiny wrapper to expose publish()
public class ExampleCloudWatchMeterRegistry extends CloudWatchMeterRegistry {

    public ExampleCloudWatchMeterRegistry(CloudWatchConfig config, Clock clock, CloudWatchAsyncClient client) {
        super(config, clock, client);
    }

    public void publishNow() {
        // StepMeterRegistry.publish() is protected, but this subclass can call it:
        this.publish();
    }
}
