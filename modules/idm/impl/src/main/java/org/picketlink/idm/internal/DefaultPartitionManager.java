/*
 * JBoss, Home of Professional Open Source
 *
 * Copyright 2013 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.picketlink.idm.internal;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.picketlink.idm.DefaultIdGenerator;
import org.picketlink.idm.IdGenerator;
import org.picketlink.idm.IdentityManagementException;
import org.picketlink.idm.IdentityManager;
import org.picketlink.idm.PartitionManager;
import org.picketlink.idm.RelationshipManager;
import org.picketlink.idm.config.FileIdentityStoreConfiguration;
import org.picketlink.idm.config.IdentityConfiguration;
import org.picketlink.idm.config.IdentityStoreConfiguration;
import org.picketlink.idm.config.IdentityStoreConfiguration.IdentityOperation;
import org.picketlink.idm.config.JPAIdentityStoreConfiguration;
import org.picketlink.idm.config.LDAPIdentityStoreConfiguration;
import org.picketlink.idm.config.OperationNotSupportedException;
import org.picketlink.idm.config.SecurityConfigurationException;
import org.picketlink.idm.credential.spi.CredentialHandler;
import org.picketlink.idm.credential.spi.annotations.SupportsCredentials;
import org.picketlink.idm.event.EventBridge;
import org.picketlink.idm.file.internal.FileIdentityStore;
import org.picketlink.idm.internal.util.RelationshipMetadata;
import org.picketlink.idm.jpa.internal.JPAIdentityStore;
import org.picketlink.idm.ldap.internal.LDAPIdentityStore;
import org.picketlink.idm.model.AttributedType;
import org.picketlink.idm.model.IdentityType;
import org.picketlink.idm.model.Partition;
import org.picketlink.idm.model.Relationship;
import org.picketlink.idm.model.annotation.IdentityPartition;
import org.picketlink.idm.model.sample.Realm;
import org.picketlink.idm.spi.IdentityContext;
import org.picketlink.idm.spi.IdentityStore;
import org.picketlink.idm.spi.PartitionStore;
import org.picketlink.idm.spi.StoreSelector;
import static org.picketlink.common.util.StringUtil.isNullOrEmpty;
import static org.picketlink.idm.IDMLogger.LOGGER;
import static org.picketlink.idm.IDMMessages.MESSAGES;
import static org.picketlink.idm.util.IDMUtil.isTypeSupported;
import static org.picketlink.idm.util.IDMUtil.toSet;

/**
 * Provides partition management functionality, and partition-specific {@link IdentityManager} instances.
 * <p/>
 * Before using this factory you need a valid {@link IdentityConfiguration}, usually created using the
 * {@link org.picketlink.idm.config.IdentityConfigurationBuilder}.
 * </p>
 *
 * @author Shane Bryzak
 */
public class DefaultPartitionManager implements PartitionManager, StoreSelector {

    private static final String DEFAULT_CONFIGURATION_NAME = "default";

    /**
     * The event bridge allows events to be "bridged" to an event bus, such as the CDI event bus
     */
    private final EventBridge eventBridge;

    /**
     * The ID generator is responsible for generating unique identifier values
     */
    private final IdGenerator idGenerator;

    /**
     * A collection of all identity configurations.  Each configuration has a unique name.
     */
    private final Collection<IdentityConfiguration> configurations;

    /**
     * Each partition is governed by a specific IdentityConfiguration, indicated by this Map.  Every IdentityConfiguration instance
     * will also be found in the configurations property above.
     */
    private final Map<Partition, IdentityConfiguration> partitionConfigurations = new ConcurrentHashMap<Partition, IdentityConfiguration>();

    /**
     * The store instances for each IdentityConfiguration, mapped by their corresponding IdentityStoreConfiguration
     */
    private final Map<IdentityConfiguration, Map<IdentityStoreConfiguration, IdentityStore<?>>> stores;

    /**
     * The IdentityConfiguration that is responsible for managing partition CRUD operations.  It is possible for this
     * value to be null, in which case partition management will not be supported.
     */
    private final IdentityConfiguration partitionManagementConfig;

    private RelationshipMetadata relationshipMetadata = new RelationshipMetadata();

    public DefaultPartitionManager(IdentityConfiguration configuration) {
        this(Arrays.asList(configuration), null, null);
    }

    public DefaultPartitionManager(Collection<IdentityConfiguration> configurations) {
        this(configurations, null, null);
    }

    /**
     * @param configurations
     * @param eventBridge
     * @param idGenerator
     */
    public DefaultPartitionManager(Collection<IdentityConfiguration> configurations, EventBridge eventBridge,
                                   IdGenerator idGenerator) {
        LOGGER.identityManagerBootstrapping();

        if (configurations == null || configurations.isEmpty()) {
            throw new IllegalArgumentException("At least one IdentityConfiguration must be provided");
        }

        this.configurations = Collections.unmodifiableCollection(configurations);

        if (eventBridge != null) {
            this.eventBridge = eventBridge;
        } else {
            this.eventBridge = new EventBridge() {
                public void raiseEvent(Object event) { /* no-op */}
            };
        }

        if (idGenerator != null) {
            this.idGenerator = idGenerator;
        } else {
            this.idGenerator = new DefaultIdGenerator();
        }

        IdentityConfiguration partitionCfg = null;
        search:
        for (IdentityConfiguration config : configurations) {
            for (IdentityStoreConfiguration storeConfig : config.getStoreConfiguration()) {
                if (storeConfig.supportsType(Partition.class, IdentityOperation.create)) {
                    partitionCfg = config;
                    break search;
                }
            }
        }
        // There may be no configuration that supports partition management, in which case the partitionManagementConfig
        // field will be null and partition management operations will not be supported
        this.partitionManagementConfig = partitionCfg;

        Map<IdentityConfiguration, Map<IdentityStoreConfiguration, IdentityStore<?>>> configuredStores =
                new HashMap<IdentityConfiguration, Map<IdentityStoreConfiguration, IdentityStore<?>>>();

        for (IdentityConfiguration config : configurations) {
            Map<IdentityStoreConfiguration, IdentityStore<?>> storeMap = new HashMap<IdentityStoreConfiguration, IdentityStore<?>>();

            for (IdentityStoreConfiguration storeConfig : config.getStoreConfiguration()) {
                @SuppressWarnings("rawtypes")
                Class<? extends IdentityStore> storeClass = storeConfig.getIdentityStoreType();
                storeMap.put(storeConfig, createIdentityStore(storeClass, storeConfig));
            }

            configuredStores.put(config, Collections.unmodifiableMap(storeMap));
        }

        stores = Collections.unmodifiableMap(configuredStores);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T extends IdentityStore> T createIdentityStore(Class<T> storeClass, IdentityStoreConfiguration storeConfiguration) {
        T store = null;

        try {
            if (storeClass == null) {
                // If no store class is configured, default to the built-in types for known configurations
                if (FileIdentityStoreConfiguration.class.isInstance(storeConfiguration)) {
                    storeClass = (Class<T>) FileIdentityStore.class;
                } else if (JPAIdentityStoreConfiguration.class.isInstance(storeConfiguration)) {
                    storeClass = (Class<T>) JPAIdentityStore.class;
                } else if (LDAPIdentityStoreConfiguration.class.isInstance(storeConfiguration)) {
                    storeClass = (Class<T>) LDAPIdentityStore.class;
                } else {
                    throw new IdentityManagementException("Unknown IdentityStore class for configuration [" + storeConfiguration + "].");
                }
            }

            store = storeClass.newInstance();
        } catch (Exception ex) {
            throw new IdentityManagementException("Error while creating IdentityStore instance for configuration [" +
                    storeConfiguration + "].", ex);
        }

        store.setup(storeConfiguration);

        return store;
    }

    private IdentityConfiguration getConfigurationByName(String name) {
        for (IdentityConfiguration config : configurations) {
            if (name.equals(config.getName())) {
                return config;
            }
        }
        return null;
    }

    private IdentityConfiguration getConfigurationForPartition(Partition partition) {
        if (partitionConfigurations.containsKey(partition)) {
            return partitionConfigurations.get(partition);
        } else {
            return lookupPartitionConfiguration(partition);
        }
    }

    private IdentityConfiguration lookupPartitionConfiguration(Partition partition) {
        if (!partitionConfigurations.containsKey(partition)) {

            IdentityContext context = createIdentityContext();
            PartitionStore<?> store = getStoreForPartitionOperation(context);
            partitionConfigurations.put(partition, getConfigurationByName(store.getConfigurationName(context, partition)));
        }

        return partitionConfigurations.get(partition);
    }

    private IdentityContext createIdentityContext() {
        return new IdentityContext() {
            private Map<String, Object> params = new HashMap<String, Object>();

            @Override
            public Object getParameter(String paramName) {
                return params.get(paramName);
            }

            @Override
            public boolean isParameterSet(String paramName) {
                return params.containsKey(paramName);
            }

            @Override
            public void setParameter(String paramName, Object value) {
                params.put(paramName, value);
            }

            @Override
            public EventBridge getEventBridge() {
                return eventBridge;
            }

            @Override
            public IdGenerator getIdGenerator() {
                return idGenerator;
            }

            @Override
            public Partition getPartition() {
                return null;
            }
        };
    }

    @Override
    public IdentityManager createIdentityManager() throws SecurityConfigurationException {
        return createIdentityManager(new Realm(Realm.DEFAULT_REALM));
    }

    @Override
    public IdentityManager createIdentityManager(Partition partition) throws SecurityConfigurationException, IdentityManagementException {
        if (partition == null) {
            throw MESSAGES.nullArgument("Partition");
        }

        Partition storedPartition = getPartition(partition.getClass(), partition.getName());

        if (storedPartition == null) {
            throw MESSAGES.partitionNotFoundWithName(partition.getClass(), partition.getName());
        }

        try {
            return new ContextualIdentityManager(storedPartition, eventBridge, idGenerator, this);
        } catch (Exception e) {
            throw MESSAGES.couldNotCreateContextualIdentityManager(storedPartition);
        }
    }

    @Override
    public RelationshipManager createRelationshipManager() {
        return new ContextualRelationshipManager(eventBridge, idGenerator, this, relationshipMetadata);
    }

    @Override
    public <T extends Partition> T getPartition(Class<T> partitionClass, String name) {
        checkPartitionManagementSupported();

        try {
            IdentityContext context = createIdentityContext();
            return getStoreForPartitionOperation(context).<T>get(context, partitionClass, name);
        } catch (Exception e) {
            throw new IdentityManagementException("Could not load partition for type [" + partitionClass.getName() + "] and name [" + name + "].");
        }
    }

    public void add(Partition partition) {
        add(partition, null);
    }

    @Override
    public void add(Partition partition, String configurationName) {
        checkPartitionManagementSupported();

        if (partition == null) {
            throw MESSAGES.nullArgument("Partition");
        }

        if (isNullOrEmpty(configurationName)) {
            configurationName = getDefaultConfigurationName();
        }

        if (getPartition(partition.getClass(), partition.getName()) != null) {
            throw MESSAGES.partitionAlreadyExistsWithName(partition.getClass(), partition.getName());
        }

        try {
            IdentityContext context = createIdentityContext();

            getStoreForPartitionOperation(context).add(context, partition, configurationName);
        } catch (Exception e) {
            throw new IdentityManagementException("Could not add partition [" + partition + "] using configuration [" + configurationName + ".", e);
        }
    }

    @Override
    public void update(Partition partition) {
        checkPartitionManagementSupported();

        try {
            IdentityContext context = createIdentityContext();
            getStoreForPartitionOperation(context).update(context, partition);
        } catch (Exception e) {
            throw new IdentityManagementException("Could not update partition [" + partition + "].", e);
        }
    }

    @Override
    public void remove(Partition partition) {
        checkPartitionManagementSupported();

        try {
            IdentityContext context = createIdentityContext();
            getStoreForPartitionOperation(context).remove(context, partition);
        } catch (Exception e) {
            throw new IdentityManagementException("Could not remove partition [" + partition + "].", e);
        }
    }

    @Override
    public <T extends IdentityStore<?>> T getStoreForIdentityOperation(IdentityContext context, Class<T> storeType,
                                                                       Class<? extends AttributedType> type, IdentityOperation operation) {
        checkSupportedTypes(context.getPartition(), type);

        IdentityConfiguration identityConfiguration = getConfigurationForPartition(context.getPartition());

        for (IdentityStoreConfiguration storeConfig : identityConfiguration.getStoreConfiguration()) {
            if (storeConfig.supportsType(type, operation)) {
                @SuppressWarnings("unchecked")
                T store = (T) stores.get(identityConfiguration).get(storeConfig);
                storeConfig.initializeContext(context, store);
                return store;
            }
        }

        throw new IdentityManagementException("No IdentityStore found for required type [" + type + "]");
    }

    @Override
    public IdentityStore<?> getStoreForCredentialOperation(IdentityContext context, Class<?> credentialClass) {
        IdentityConfiguration config = getConfigurationForPartition(context.getPartition());
        for (IdentityStoreConfiguration storeConfig : config.getStoreConfiguration()) {
            for (@SuppressWarnings("rawtypes") Class<? extends CredentialHandler> handlerClass : storeConfig.getCredentialHandlers()) {
                if (handlerClass.isAnnotationPresent(SupportsCredentials.class)) {
                    for (Class<?> cls : handlerClass.getAnnotation(SupportsCredentials.class).value()) {
                        if (cls.equals(credentialClass)) {
                            IdentityStore<?> store = stores.get(config).get(storeConfig);
                            storeConfig.initializeContext(context, store);
                            return store;
                        }
                    }
                }
            }
        }

        throw new IdentityManagementException("No IdentityStore found for credential class [" + credentialClass + "]");
    }

    @Override
    public IdentityStore<?> getStoreForRelationshipOperation(IdentityContext context, Class<? extends Relationship> relationshipClass,
                                                             Set<Partition> partitions) {

        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public PartitionStore<?> getStoreForPartitionOperation(IdentityContext context) {
        Map<IdentityStoreConfiguration, IdentityStore<?>> configStores = stores.get(partitionManagementConfig);
        for (IdentityStoreConfiguration cfg : configStores.keySet()) {
            if (cfg.supportsType(Partition.class, IdentityOperation.create)) {
                PartitionStore<?> store = (PartitionStore<?>) configStores.get(cfg);
                cfg.initializeContext(context, store);
                return store;
            }
        }
        throw new IdentityManagementException("Could not locate PartitionStore");
    }

    private String getDefaultConfigurationName() {
        // If there is a configuration with the default configuration name, return that name
        for (IdentityConfiguration config : configurations) {
            if (DEFAULT_CONFIGURATION_NAME.equals(config.getName())) {
                return DEFAULT_CONFIGURATION_NAME;
            }
        }

        // Otherwise return the first configuration found
        return configurations.iterator().next().getName();
    }

    private void checkPartitionManagementSupported() {
        if (partitionManagementConfig == null) {
            throw new OperationNotSupportedException(
                    "Partition management is not supported by the current configuration", Partition.class, IdentityOperation.create);
        }
    }

    private void checkSupportedTypes(Partition partition, Class<? extends AttributedType> type) {
        if (IdentityType.class.isAssignableFrom(type)) {
            IdentityPartition identityPartition = partition.getClass().getAnnotation(IdentityPartition.class);

            if (identityPartition != null
                    && isTypeSupported((Class<? extends IdentityType>) type, toSet(identityPartition.supportedTypes()),
                    toSet(identityPartition.unsupportedTypes())) == -1) {
                throw new IdentityManagementException("Partition [" + partition + "] does not support type [" + type + "].");
            }
        }
    }
}