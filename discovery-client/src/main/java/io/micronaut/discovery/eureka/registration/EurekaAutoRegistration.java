/*
 * Copyright 2017-2018 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.micronaut.discovery.eureka.registration;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.core.convert.value.ConvertibleValues;
import io.micronaut.discovery.ServiceInstance;
import io.micronaut.discovery.ServiceInstanceIdGenerator;
import io.micronaut.discovery.client.registration.DiscoveryServiceAutoRegistration;
import io.micronaut.discovery.eureka.EurekaConfiguration;
import io.micronaut.discovery.eureka.client.v2.EurekaClient;
import io.micronaut.discovery.eureka.client.v2.InstanceInfo;
import io.micronaut.health.HealthStatus;
import io.micronaut.health.HeartbeatConfiguration;
import io.micronaut.http.HttpStatus;
import io.micronaut.runtime.ApplicationConfiguration;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import javax.inject.Singleton;

/**
 * A {@link io.micronaut.discovery.registration.AutoRegistration} that registers with Eureka.
 *
 * @author Graeme Rocher
 * @since 1.0
 */
@Singleton
@Requires(beans = EurekaClient.class)
@Requires(property = ApplicationConfiguration.APPLICATION_NAME)
public class EurekaAutoRegistration extends DiscoveryServiceAutoRegistration {

    private static final String EUREKA_SERVICE_NAME = "Eureka";
    private final Environment environment;
    private final EurekaClient eurekaClient;
    private final EurekaConfiguration eurekaConfiguration;
    private final HeartbeatConfiguration heartbeatConfiguration;
    private final ServiceInstanceIdGenerator idGenerator;

    /**
     * @param environment            The environment
     * @param eurekaClient           The Eureka client
     * @param eurekaConfiguration    The Eureka configuration
     * @param heartbeatConfiguration The Heartbeat configuration
     * @param idGenerator            The id generator
     */
    protected EurekaAutoRegistration(
        Environment environment,
        EurekaClient eurekaClient,
        EurekaConfiguration eurekaConfiguration,
        HeartbeatConfiguration heartbeatConfiguration,
        ServiceInstanceIdGenerator idGenerator) {

        super(eurekaConfiguration.getRegistration());
        this.environment = environment;
        this.eurekaClient = eurekaClient;
        this.eurekaConfiguration = eurekaConfiguration;
        this.heartbeatConfiguration = heartbeatConfiguration;
        this.idGenerator = idGenerator;
    }

    @Override
    protected void pulsate(ServiceInstance instance, HealthStatus status) {
        EurekaConfiguration.EurekaRegistrationConfiguration registration = eurekaConfiguration.getRegistration();
        if (heartbeatConfiguration.isEnabled() && registration != null) {
            InstanceInfo instanceInfo = registration.getInstanceInfo();
            if (status.equals(HealthStatus.UP)) {
                eurekaClient.heartbeat(instanceInfo.getApp(), instanceInfo.getId()).subscribe(new Subscriber<HttpStatus>() {
                    @Override
                    public void onSubscribe(Subscription s) {
                        s.request(1);
                    }

                    @Override
                    public void onNext(HttpStatus httpStatus) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("Successfully reported passing state to Eureka");
                        }
                    }

                    @Override
                    public void onError(Throwable t) {
                        String errorMessage = getErrorMessage(t, "Error reporting passing state to Eureka: ");
                        if (LOG.isErrorEnabled()) {
                            LOG.error(errorMessage, t);
                        }
                    }

                    @Override
                    public void onComplete() {
                        // no-op
                    }
                });
            } else {
                InstanceInfo.Status s = translateState(status);
                eurekaClient.updateStatus(instanceInfo.getApp(), instanceInfo.getId(), s)
                    .subscribe(new Subscriber<HttpStatus>() {
                        @Override
                        public void onSubscribe(Subscription s) {
                            s.request(1);
                        }

                        @Override
                        public void onNext(HttpStatus httpStatus) {
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("Successfully reported status {} to Eureka", s);
                            }
                        }

                        @Override
                        public void onError(Throwable t) {
                            String errorMessage = getErrorMessage(t, "Error reporting state to Eureka: ");
                            if (LOG.isErrorEnabled()) {
                                LOG.error(errorMessage, t);
                            }
                        }

                        @Override
                        public void onComplete() {
                            // no-op
                        }
                    });
            }
        }
    }

    /**
     * Translate a {@link HealthStatus} to a Eureka {@link io.micronaut.discovery.eureka.client.v2.InstanceInfo.Status}.
     *
     * @param status The status
     * @return The {@link io.micronaut.discovery.eureka.client.v2.InstanceInfo.Status} instance
     */
    protected InstanceInfo.Status translateState(HealthStatus status) {
        if (status.equals(HealthStatus.UP)) {
            return InstanceInfo.Status.UP;
        }
        return InstanceInfo.Status.DOWN;
    }

    @Override
    protected void deregister(ServiceInstance instance) {
        EurekaConfiguration.EurekaRegistrationConfiguration registration = eurekaConfiguration.getRegistration();
        if (registration != null) {
            InstanceInfo instanceInfo = registration.getInstanceInfo();

            Publisher<HttpStatus> deregisterPublisher = eurekaClient.deregister(instanceInfo.getApp(), instanceInfo.getId());
            performDeregistration(EUREKA_SERVICE_NAME, registration, deregisterPublisher, instanceInfo.getApp());
        }
    }

    @Override
    protected void register(ServiceInstance instance) {
        EurekaConfiguration.EurekaRegistrationConfiguration registration = eurekaConfiguration.getRegistration();
        if (registration != null) {
            InstanceInfo instanceInfo = registration.getInstanceInfo();

            if (!registration.isExplicitInstanceId()) {
                instanceInfo.setInstanceId(idGenerator.generateId(environment, instance));
            }

            ConvertibleValues<String> instanceMetadata = instance.getMetadata();
            instanceInfo.getMetadata().putAll(instanceMetadata.asMap(String.class, String.class));
            customizeInstanceInfo(instanceInfo);
            validateApplicationName(instanceInfo.getApp());

            Publisher<HttpStatus> registerPublisher = eurekaClient.register(instanceInfo.getApp(), instanceInfo);
            performRegistration(EUREKA_SERVICE_NAME, registration, instance, registerPublisher);
        }
    }

    /**
     * Subclasses can override to customize the instance info.
     *
     * @param instanceInfo The instance info
     */
    protected void customizeInstanceInfo(InstanceInfo instanceInfo) {
        // no-op
    }
}
