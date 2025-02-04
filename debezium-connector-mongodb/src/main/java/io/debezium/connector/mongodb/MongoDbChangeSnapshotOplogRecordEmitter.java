/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.mongodb;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.connect.data.Struct;
import org.bson.Document;

import io.debezium.annotation.Immutable;
import io.debezium.data.Envelope.FieldName;
import io.debezium.data.Envelope.Operation;
import io.debezium.pipeline.AbstractChangeRecordEmitter;
import io.debezium.pipeline.spi.OffsetContext;
import io.debezium.pipeline.spi.Partition;
import io.debezium.util.Clock;

/**
 * Emits change data based on a collection document.
 *
 * @author Chris Cranford
 */
public class MongoDbChangeSnapshotOplogRecordEmitter extends AbstractChangeRecordEmitter<MongoDbCollectionSchema> {

    private final Document oplogEvent;

    /**
     * Whether this event originates from a snapshot.
     */
    private final boolean isSnapshot;

    @Immutable
    private static final Map<String, Operation> OPERATION_LITERALS;

    static {
        Map<String, Operation> literals = new HashMap<>();

        literals.put("i", Operation.CREATE);
        literals.put("u", Operation.UPDATE);
        literals.put("d", Operation.DELETE);

        OPERATION_LITERALS = Collections.unmodifiableMap(literals);
    }

    public MongoDbChangeSnapshotOplogRecordEmitter(Partition partition, OffsetContext offsetContext, Clock clock, Document oplogEvent, boolean isSnapshot) {
        super(partition, offsetContext, clock);
        this.oplogEvent = oplogEvent;
        this.isSnapshot = isSnapshot;
    }

    @Override
    public Operation getOperation() {
        if (isSnapshot || oplogEvent.getString("op") == null) {
            return Operation.READ;
        }
        return OPERATION_LITERALS.get(oplogEvent.getString("op"));
    }

    @Override
    protected void emitReadRecord(Receiver receiver, MongoDbCollectionSchema schema) throws InterruptedException {
        final Object newKey = schema.keyFromDocument(oplogEvent);
        assert newKey != null;

        final Struct value = schema.valueFromDocumentOplog(oplogEvent, null, getOperation());
        value.put(FieldName.SOURCE, getOffset().getSourceInfo());
        value.put(FieldName.OPERATION, getOperation().code());
        value.put(FieldName.TIMESTAMP, getClock().currentTimeAsInstant().toEpochMilli());

        receiver.changeRecord(getPartition(), schema, getOperation(), newKey, value, getOffset(), null);
    }

    @Override
    protected void emitCreateRecord(Receiver receiver, MongoDbCollectionSchema schema) throws InterruptedException {
        createAndEmitChangeRecord(receiver, schema);
    }

    @Override
    protected void emitUpdateRecord(Receiver receiver, MongoDbCollectionSchema schema) throws InterruptedException {
        createAndEmitChangeRecord(receiver, schema);
    }

    @Override
    protected void emitDeleteRecord(Receiver receiver, MongoDbCollectionSchema schema) throws InterruptedException {
        createAndEmitChangeRecord(receiver, schema);
    }

    private void createAndEmitChangeRecord(Receiver receiver, MongoDbCollectionSchema schema) throws InterruptedException {
        Document patchObject = oplogEvent.get("o", Document.class);
        // Updates have an 'o2' field, since the updated object in 'o' might not have the ObjectID
        Document queryObject = oplogEvent.get("o2", Document.class);

        final Document filter = queryObject != null ? queryObject : patchObject;

        final Object newKey = schema.keyFromDocument(filter);
        assert newKey != null;

        final Struct value = schema.valueFromDocumentOplog(patchObject, filter, getOperation());
        value.put(FieldName.SOURCE, getOffset().getSourceInfo());
        value.put(FieldName.OPERATION, getOperation().code());
        value.put(FieldName.TIMESTAMP, getClock().currentTimeAsInstant().toEpochMilli());

        receiver.changeRecord(getPartition(), schema, getOperation(), newKey, value, getOffset(), null);
    }

    public static boolean isValidOperation(String operation) {
        return OPERATION_LITERALS.containsKey(operation);
    }
}
