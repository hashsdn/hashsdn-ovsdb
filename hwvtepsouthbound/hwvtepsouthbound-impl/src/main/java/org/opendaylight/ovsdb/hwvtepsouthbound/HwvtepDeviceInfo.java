/*
 * Copyright (c) 2016, 2017 Ericsson India Global Services Pvt Ltd. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.ovsdb.hwvtepsouthbound;

import com.google.common.collect.Sets;
import org.opendaylight.ovsdb.hwvtepsouthbound.transact.DependencyQueue;
import org.opendaylight.ovsdb.hwvtepsouthbound.transact.DependentJob;
import org.opendaylight.ovsdb.hwvtepsouthbound.transact.TransactCommand;
import org.opendaylight.ovsdb.lib.notation.UUID;
import org.opendaylight.ovsdb.schema.hardwarevtep.LogicalSwitch;
import org.opendaylight.ovsdb.schema.hardwarevtep.PhysicalLocator;
import org.opendaylight.ovsdb.schema.hardwarevtep.PhysicalSwitch;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.ovsdb.hwvtep.rev150901.hwvtep.global.attributes.LogicalSwitches;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.ovsdb.hwvtep.rev150901.hwvtep.global.attributes.RemoteMcastMacs;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.ovsdb.hwvtep.rev150901.hwvtep.global.attributes.RemoteUcastMacs;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.Node;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.node.TerminationPoint;
import org.opendaylight.yangtools.yang.binding.DataObject;
import org.opendaylight.yangtools.yang.binding.Identifiable;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/*
 * HwvtepDeviceInfo is used to store some of the table entries received
 * in updates from a Hwvtep device. There will be one instance of this per
 * Hwvtep device connected. Table entries are stored in a map keyed by
 * uuids of respective rows.
 *
 * Purpose of this class is to provide data present in tables which
 * were updated in a previous transaction and are not available in
 * current updatedRows. This allows us to handle updates for Tables
 * which reference other tables and need information in those tables
 * to add data to Operational data store.
 *
 * e.g. Mac-entries in data store use logical-switch-ref as one of the
 * keys. Mac-entry updates from switch rarely contain Logical_Switch
 * table entries. To add mac-entries we need table entries from
 * Logical_Switch table which were created in an earlier update.
 *
 */
public class HwvtepDeviceInfo {

    private static final Logger LOG = LoggerFactory.getLogger(HwvtepDeviceInfo.class);

    public enum DeviceDataStatus {
        IN_TRANSIT,
        UNAVAILABLE,
        AVAILABLE
    }

    public static class DeviceData {
        private final InstanceIdentifier key;
        private final UUID uuid;
        private final Object data;
        private final DeviceDataStatus status;
        private long intransitTimeStamp;

        DeviceData(InstanceIdentifier key, UUID uuid, Object data, DeviceDataStatus status) {
            this.data = data;
            this.key = key;
            this.status = status;
            this.uuid = uuid;
            if (status == DeviceDataStatus.IN_TRANSIT) {
                intransitTimeStamp = System.currentTimeMillis();
            }
        }

        public Object getData() {
            return data;
        }

        public DeviceDataStatus getStatus() {
            return status;
        }

        public UUID getUuid() {
            return uuid;
        }

        public InstanceIdentifier getKey() {
            return key;
        }

        public boolean isIntransitTimeExpired() {
            return System.currentTimeMillis()
                    > intransitTimeStamp + HwvtepSouthboundConstants.IN_TRANSIT_STATE_EXPIRY_TIME_MILLIS;
        }

        public boolean isInTransitState() {
            return status == DeviceDataStatus.IN_TRANSIT;
        }
    }

    private Map<InstanceIdentifier, Set<InstanceIdentifier>> tepIdReferences;
    private Map<InstanceIdentifier<LogicalSwitches>, Map<InstanceIdentifier<RemoteUcastMacs>, RemoteUcastMacs>> logicalSwitchVsUcasts;
    private Map<InstanceIdentifier<LogicalSwitches>, Map<InstanceIdentifier<RemoteMcastMacs>, RemoteMcastMacs>> logicalSwitchVsMcasts;
    private Map<UUID, LogicalSwitch> logicalSwitches = null;
    private Map<UUID, PhysicalSwitch> physicalSwitches = null;
    private Map<UUID, PhysicalLocator> physicalLocators = null;
    private Map<UUID, UUID> mapTunnelToPhysicalSwitch = null;

    private HwvtepConnectionInstance connectionInstance;

    private Map<Class<? extends Identifiable>, Map<InstanceIdentifier, DeviceData>> configKeyVsData = new ConcurrentHashMap<>();
    private Map<Class<? extends Identifiable>, Map<InstanceIdentifier, DeviceData>> opKeyVsData = new ConcurrentHashMap<>();
    private Map<Class<? extends Identifiable>, Map<UUID, Object>> uuidVsData = new ConcurrentHashMap<>();
    private DependencyQueue dependencyQueue;

    public HwvtepDeviceInfo(HwvtepConnectionInstance hwvtepConnectionInstance) {
        this.connectionInstance = hwvtepConnectionInstance;
        this.logicalSwitches = new ConcurrentHashMap<>();
        this.physicalSwitches = new ConcurrentHashMap<>();
        this.physicalLocators = new ConcurrentHashMap<>();
        this.mapTunnelToPhysicalSwitch = new ConcurrentHashMap<>();
        this.tepIdReferences = new ConcurrentHashMap<>();
        this.logicalSwitchVsUcasts = new ConcurrentHashMap<>();
        this.logicalSwitchVsMcasts = new ConcurrentHashMap<>();
        this.dependencyQueue = new DependencyQueue(this);
    }

    public LogicalSwitch getLogicalSwitch(UUID uuid) {
        return (LogicalSwitch) getDeviceOperData(LogicalSwitches.class, uuid);
    }

    public Map<UUID, LogicalSwitch> getLogicalSwitches() {
        Map<UUID, Object> switches = uuidVsData.get(LogicalSwitches.class);
        Map<UUID, LogicalSwitch> result = new HashMap<>();
        if (switches != null) {
            for (Map.Entry<UUID, Object> entry : switches.entrySet()) {
                result.put(entry.getKey(), (LogicalSwitch) entry.getValue());
            }
        }
        return result;
    }

    public void putPhysicalSwitch(UUID uuid, PhysicalSwitch pSwitch) {
        physicalSwitches.put(uuid, pSwitch);
    }

    public PhysicalSwitch getPhysicalSwitch(UUID uuid) {
        return physicalSwitches.get(uuid);
    }

    public PhysicalSwitch removePhysicalSwitch(UUID uuid) {
        return physicalSwitches.remove(uuid);
    }

    public Map<UUID, PhysicalSwitch> getPhysicalSwitches() {
        return physicalSwitches;
    }

    public PhysicalLocator getPhysicalLocator(UUID uuid) {
        return (PhysicalLocator) getDeviceOperData(TerminationPoint.class, uuid);
    }

    public Map<UUID, PhysicalLocator> getPhysicalLocators() {
        Map<UUID, Object> locators = uuidVsData.get(TerminationPoint.class);
        Map<UUID, PhysicalLocator> result = new HashMap<>();
        if (locators != null) {
            for (Map.Entry<UUID, Object> entry : locators.entrySet()) {
                result.put(entry.getKey(), (PhysicalLocator) entry.getValue());
            }
        }
        return result;
    }

    public void putPhysicalSwitchForTunnel(UUID uuid, UUID psUUID) {
        mapTunnelToPhysicalSwitch.put(uuid, psUUID);
    }

    public PhysicalSwitch getPhysicalSwitchForTunnel(UUID uuid) {
        return physicalSwitches.get(mapTunnelToPhysicalSwitch.get(uuid));
    }

    public void removePhysicalSwitchForTunnel(UUID uuid) {
        mapTunnelToPhysicalSwitch.remove(uuid);
    }

    public Map<UUID, UUID> getPhysicalSwitchesForTunnels() {
        return mapTunnelToPhysicalSwitch;
    }

    public boolean isKeyInTransit(Class<? extends Identifiable> cls, InstanceIdentifier key) {
        DeviceData deviceData = HwvtepSouthboundUtil.getData(opKeyVsData, cls, key);
        return deviceData != null && DeviceDataStatus.IN_TRANSIT == deviceData.status;
    }

    public boolean isConfigDataAvailable(Class<? extends Identifiable> cls, InstanceIdentifier key) {
        return HwvtepSouthboundUtil.getData(configKeyVsData, cls, key) != null;
    }

    public void updateConfigData(Class<? extends Identifiable> cls, InstanceIdentifier key, Object data) {
        HwvtepSouthboundUtil.updateData(configKeyVsData, cls, key,
                new DeviceData(key, null, data, DeviceDataStatus.AVAILABLE));
    }

    public Object getConfigData(Class<? extends Identifiable> cls, InstanceIdentifier key) {
        DeviceData deviceData = HwvtepSouthboundUtil.getData(configKeyVsData, cls, key);
        if (deviceData != null) {
            return deviceData.getData();
        }
        return null;
    }

    public void clearConfigData(Class<? extends Identifiable> cls, InstanceIdentifier key) {
        HwvtepSouthboundUtil.clearData(configKeyVsData, cls, key);
    }

    public void markKeyAsInTransit(Class<? extends Identifiable> cls, InstanceIdentifier key) {
        LOG.debug("Marking device data as intransit {}", key);
        DeviceData deviceData = getDeviceOperData(cls, key);
        UUID uuid = null;
        Object data = null;
        if (deviceData != null) {
            uuid = deviceData.getUuid();
            data = deviceData.getData();
        }
        HwvtepSouthboundUtil.updateData(opKeyVsData, cls, key,
                new DeviceData(key, uuid, data, DeviceDataStatus.IN_TRANSIT));
    }

    public void updateDeviceOperData(Class<? extends Identifiable> cls, InstanceIdentifier key, UUID uuid, Object data) {
        LOG.debug("Updating device data {}", key);
        HwvtepSouthboundUtil.updateData(opKeyVsData, cls, key,
                new DeviceData(key, uuid, data, DeviceDataStatus.AVAILABLE));
        HwvtepSouthboundUtil.updateData(uuidVsData, cls, uuid, data);
    }

    public void clearDeviceOperData(Class<? extends Identifiable> cls, InstanceIdentifier key) {
        DeviceData deviceData = HwvtepSouthboundUtil.getData(opKeyVsData, cls, key);
        if (deviceData != null && deviceData.uuid != null) {
            HwvtepSouthboundUtil.clearData(uuidVsData, cls, deviceData.uuid);
        }
        HwvtepSouthboundUtil.clearData(opKeyVsData, cls, key);
    }

    public Object getDeviceOperData(Class<? extends Identifiable> cls, UUID uuid) {
        return HwvtepSouthboundUtil.getData(uuidVsData, cls, uuid);
    }

    public DeviceData getDeviceOperData(Class<? extends Identifiable> cls, InstanceIdentifier key) {
        return HwvtepSouthboundUtil.getData(opKeyVsData, cls, key);
    }

    public UUID getUUID(Class<? extends Identifiable> cls, InstanceIdentifier key) {
        DeviceData data = HwvtepSouthboundUtil.getData(opKeyVsData, cls, key);
        if (data != null) {
            return data.uuid;
        }
        return null;
    }

    public <T extends Identifiable> void addJobToQueue(DependentJob<T> job) {
        dependencyQueue.addToQueue(job);
    }

    public void onConfigDataAvailable() {
        dependencyQueue.processReadyJobsFromConfigQueue(connectionInstance);
    }

    public synchronized void onOperDataAvailable() {
        dependencyQueue.processReadyJobsFromOpQueue(connectionInstance);
    }

    public void scheduleTransaction(final TransactCommand transactCommand) {
        dependencyQueue.submit(() -> connectionInstance.transact(transactCommand));
    }

    public void clearDeviceOperData(Class<? extends Identifiable> cls) {
        Map<InstanceIdentifier, DeviceData> iids = opKeyVsData.get(cls);
        if (iids != null && !iids.isEmpty()) {
            Iterator<Map.Entry<InstanceIdentifier, DeviceData>> it = iids.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<InstanceIdentifier, DeviceData> entry = it.next();
                DeviceData deviceData = entry.getValue();
                if (deviceData != null && deviceData.getStatus() != DeviceDataStatus.IN_TRANSIT) {
                    it.remove();
                }
            }
        }
    }

    public void clearInTransit(Class<? extends Identifiable> cls, InstanceIdentifier key) {
        DeviceData deviceData = getDeviceOperData(cls, key);
        if (deviceData != null && deviceData.isInTransitState()) {
            if (deviceData.getData() != null) {
                HwvtepSouthboundUtil.updateData(opKeyVsData, cls, key,
                        new DeviceData(key, deviceData.getUuid(), deviceData.getData(), DeviceDataStatus.AVAILABLE));
            } else {
                clearDeviceOperData(cls, key);
            }
        }
    }

    public Map<InstanceIdentifier, DeviceData> getDeviceOperData(Class<? extends Identifiable> cls) {
        return opKeyVsData.get(cls);
    }

    public void incRefCount(InstanceIdentifier reference, InstanceIdentifier tep) {
        if (reference == null || tep == null) {
            return;
        }
        tepIdReferences.computeIfAbsent(tep, (tepId) -> Sets.newConcurrentHashSet());
        tepIdReferences.get(tep).add(reference);
    }

    public int getRefCount(InstanceIdentifier tep) {
        return tepIdReferences.containsKey(tep) ? tepIdReferences.get(tep).size() : 0;
    }

    public Set<InstanceIdentifier> getRefCounts(InstanceIdentifier tep) {
        return tepIdReferences.get(tep);
    }

    public void decRefCount(InstanceIdentifier reference, InstanceIdentifier tep) {
        if (reference == null || tep == null || !tepIdReferences.containsKey(tep)) {
            return;
        }
        //synchronize to make sure that no two parallel deletes puts the key in transit state twice
        synchronized (this) {
            boolean removed = tepIdReferences.get(tep).remove(reference);
            if (removed && tepIdReferences.get(tep).isEmpty()) {
                LOG.debug("Marking the termination point as in transit ref count zero {} ", tep);
                markKeyAsInTransit(TerminationPoint.class, tep);
            }
        }
    }

    public void clearLogicalSwitchRefs(InstanceIdentifier<LogicalSwitches> logicalSwitchKey) {
        Map<InstanceIdentifier<RemoteMcastMacs>, RemoteMcastMacs> mcasts = logicalSwitchVsMcasts.get(logicalSwitchKey);
        if (mcasts != null ) {
            mcasts.entrySet().forEach( (entry) -> removeRemoteMcast(logicalSwitchKey, entry.getKey()));
        }
        Map<InstanceIdentifier<RemoteUcastMacs>, RemoteUcastMacs> ucasts = logicalSwitchVsUcasts.get(logicalSwitchKey);
        if (ucasts != null ) {
            ucasts.entrySet().forEach( (entry) -> removeRemoteUcast(logicalSwitchKey, entry.getKey()));
        }
        markKeyAsInTransit(LogicalSwitches.class, logicalSwitchKey);
    }

    public  void updateRemoteMcast(InstanceIdentifier<LogicalSwitches> lsIid,
                                   InstanceIdentifier<RemoteMcastMacs> mcastIid,
                                   RemoteMcastMacs mac) {
        logicalSwitchVsMcasts.computeIfAbsent(lsIid, (lsKey) -> new ConcurrentHashMap<>());
        logicalSwitchVsMcasts.get(lsIid).put(mcastIid, mac);
        if (mac.getLocatorSet() != null) {
            mac.getLocatorSet().forEach( (iid) -> incRefCount(mcastIid, iid.getLocatorRef().getValue()));
        }
    }

    public  void updateRemoteUcast(InstanceIdentifier<LogicalSwitches> lsIid,
                                   InstanceIdentifier<RemoteUcastMacs> ucastIid,
                                   RemoteUcastMacs mac) {
        logicalSwitchVsUcasts.computeIfAbsent(lsIid, (lsKey) -> new ConcurrentHashMap<>());
        logicalSwitchVsUcasts.get(lsIid).put(ucastIid, mac);
        incRefCount(ucastIid, mac.getLocatorRef().getValue());
    }

    public  void removeRemoteMcast(InstanceIdentifier<LogicalSwitches> lsIid, InstanceIdentifier<RemoteMcastMacs> mcastIid) {
        if (!logicalSwitchVsMcasts.containsKey(lsIid)) {
            return;
        }
        RemoteMcastMacs mac = logicalSwitchVsMcasts.get(lsIid).remove(mcastIid);
        if (mac != null && mac.getLocatorSet() != null) {
            mac.getLocatorSet().forEach((iid) -> decRefCount(mcastIid, iid.getLocatorRef().getValue()));
        }
        markKeyAsInTransit(RemoteMcastMacs.class, mcastIid);
    }

    public void removeRemoteUcast(InstanceIdentifier<LogicalSwitches> lsIid,
                                   InstanceIdentifier<RemoteUcastMacs> ucastIid) {
        if (!logicalSwitchVsUcasts.containsKey(lsIid)) {
            return;
        }
        RemoteUcastMacs mac = logicalSwitchVsUcasts.get(lsIid).remove(ucastIid);
        if (mac != null) {
            decRefCount(ucastIid, mac.getLocatorRef().getValue());
        }
        markKeyAsInTransit(RemoteUcastMacs.class, ucastIid);
    }

    public HwvtepConnectionInstance getConnectionInstance() {
        return connectionInstance;
    }
}
