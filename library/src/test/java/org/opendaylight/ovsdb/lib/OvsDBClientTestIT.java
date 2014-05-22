/*
 * Copyright (C) 2014 EBay Software Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 *
 * Authors : Ashwin Raveendran
 */
package org.opendaylight.ovsdb.lib;

import static org.opendaylight.ovsdb.lib.operations.Operations.op;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import junit.framework.Assert;

import org.junit.Before;
import org.junit.Test;
import org.opendaylight.ovsdb.lib.message.MonitorRequest;
import org.opendaylight.ovsdb.lib.message.MonitorRequestBuilder;
import org.opendaylight.ovsdb.lib.message.MonitorSelect;
import org.opendaylight.ovsdb.lib.message.OvsdbRPC;
import org.opendaylight.ovsdb.lib.message.TableUpdate;
import org.opendaylight.ovsdb.lib.message.TableUpdates;
import org.opendaylight.ovsdb.lib.message.UpdateNotification;
import org.opendaylight.ovsdb.lib.notation.OvsDBSet;
import org.opendaylight.ovsdb.lib.operations.OperationResult;
import org.opendaylight.ovsdb.lib.schema.ColumnSchema;
import org.opendaylight.ovsdb.lib.schema.DatabaseSchema;
import org.opendaylight.ovsdb.lib.schema.GenericTableSchema;
import org.opendaylight.ovsdb.lib.schema.TableSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ListenableFuture;


public class OvsDBClientTestIT extends OvsdbTestBase {
    Logger logger = LoggerFactory.getLogger(OvsDBClientTestIT.class);

    OvsDBClientImpl ovs;

    @Test
    public void testTransact() throws IOException, InterruptedException, ExecutionException {

        ListenableFuture<DatabaseSchema> schema = ovs.getSchema(OvsDBClient.OPEN_VSWITCH_SCHEMA, true);
        TableSchema<GenericTableSchema> bridge = schema.get().table("Bridge", GenericTableSchema.class);

        for (Map.Entry<String, ColumnSchema> names : bridge.getColumnSchemas().entrySet()) {
            System.out.println("names = " + names.getKey());
            System.out.println("names.getValue().getType() = " + names.getValue().getType().getBaseType());
        }

        ColumnSchema<GenericTableSchema, String> name = bridge.column("name", String.class);
        ColumnSchema<GenericTableSchema, String> fail_mode = bridge.column("fail_mode", String.class);

        /*
         * Adding a Test around non-atomic columns, since Set/Map columns requires special
         * serialization/deserailzation for OVSDB Bidirection JSON.
         * TODO : Replace this with regular Set and hide the ugliness of OvsDBSet inside the API.
         */
        ColumnSchema<GenericTableSchema, OvsDBSet> flood_vlans = bridge.column("flood_vlans", OvsDBSet.class);
        OvsDBSet vlans = new OvsDBSet();
        vlans.addAll(Sets.newHashSet(100, 200));
        ListenableFuture<List<OperationResult>> results = ovs.transactBuilder()
                .add(op.insert(bridge)
                     .value(name, "br-int")
                     .value(flood_vlans, vlans))
                .add(op.update(bridge)
                        .set(fail_mode, "secure")
                        .where(name.opEqual("br-int"))
                        //.and(name.opEqual("br-int"))
                        .operation())
                .add(op.select(bridge)
                        .column(name)
                        .where(name.opEqual("br-int"))
                        .operation())
                .add(op.commit(true))
                .execute();

        List<OperationResult> operationResults = results.get();
        Assert.assertFalse(operationResults.isEmpty());
        System.out.println("Insert & Update operation results = " + operationResults);

        results = ovs.transactBuilder()
                .add(op.delete(bridge)
                        .where(name.opEqual("br-int"))
                        .operation())
                .add(op.commit(true))
                .execute();

        operationResults = results.get();
        Assert.assertFalse(operationResults.isEmpty());
        System.out.println("Delete operation results = " + operationResults);

        /*
         * Adding a separate Abort operation in a transaction. Lets not mix this with other
         * valid transactions as above.
         */
        results = ovs.transactBuilder()
                .add(op.delete(bridge)
                        .where(name.opEqual("br-int"))
                        .operation())
                .add(op.abort())
                .execute();

        operationResults = results.get();
        Assert.assertFalse(operationResults.isEmpty());
        System.out.println("Abort operation results = " + operationResults);

    }

    @Test
    public void testMonitorRequest() throws ExecutionException, InterruptedException {

        DatabaseSchema dbSchema = ovs.getSchema(OvsDBClient.OPEN_VSWITCH_SCHEMA, true).get();
        GenericTableSchema bridge = dbSchema.table("Bridge", GenericTableSchema.class);

        List<MonitorRequest<GenericTableSchema>> monitorRequests = Lists.newArrayList();
        monitorRequests.add(
                MonitorRequestBuilder.builder(bridge)
                        .addColumn(bridge.column("name"))
                        .addColumn(bridge.column("fail_mode", String.class))
                        .addColumn(bridge.column("flood_vlans"))
                        .with(new MonitorSelect(true, true, true, true))
                        .build());

        final List<Object> results = Lists.newArrayList();

        MonitorHandle monitor = ovs.monitor(dbSchema, monitorRequests, new MonitorCallBack() {
            @Override
            public void update(TableUpdates result) {
                results.add(result);
                System.out.println("result = " + result);
            }

            @Override
            public void exception(Throwable t) {
                results.add(t);
                System.out.println("t = " + t);
            }
        });

        //for (int i = 0; i < 5 && results.isEmpty(); i++) { //wait 5 seconds to get a result
        for (int i = 0; i < 5 ; i++) { //wait 5 seconds to get a result
            System.out.println("waiting");
            Thread.sleep(1000);
        }

        Assert.assertTrue(!results.isEmpty());
        Object result = results.get(0);
        Assert.assertTrue(result instanceof TableUpdates);
        TableUpdate bridgeUpdate = ((TableUpdates) result).getUpdate(bridge);
        Assert.assertNotNull(bridgeUpdate);
    }

    @Test
    public void testGetDBs() throws ExecutionException, InterruptedException {
        ListenableFuture<List<String>> databases = ovs.getDatabases();
        List<String> dbNames = databases.get();
        Assert.assertNotNull(dbNames);
        Assert.assertTrue(dbNames.size() > 0);
    }

    @Before
    public  void initalize() throws IOException {
        if (ovs != null) {
            return;
        }
        OvsdbRPC rpc = getTestConnection();
        if (rpc == null) {
            System.out.println("Unable to Establish Test Connection");
        }
        ExecutorService executorService = Executors.newFixedThreadPool(3);
        ovs = new OvsDBClientImpl(rpc, executorService);
    }


    @Override
    public void update(Object node, UpdateNotification upadateNotification) {
        // TODO Auto-generated method stub

    }

    @Override
    public void locked(Object node, List<String> ids) {
        // TODO Auto-generated method stub

    }
    @Override
    public void stolen(Object node, List<String> ids) {
        // TODO Auto-generated method stub

    }
}
