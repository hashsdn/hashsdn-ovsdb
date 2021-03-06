/*
 * Copyright (c) 2014, 2015 EBay Software Foundation and others. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.ovsdb.lib.schema;

import com.fasterxml.jackson.databind.JsonNode;
import org.opendaylight.ovsdb.lib.error.TyperException;
import org.opendaylight.ovsdb.lib.jsonrpc.JsonUtils;
import org.opendaylight.ovsdb.lib.notation.OvsdbMap;
import org.opendaylight.ovsdb.lib.notation.OvsdbSet;


public abstract class ColumnType {
    BaseType baseType;
    long min = 1;
    long max = 1;

    public long getMin() {
        return min;
    }

    void setMin(long min) {
        this.min = min;
    }

    public long getMax() {
        return max;
    }

    void setMax(long max) {
        this.max = max;
    }

    private static ColumnType[] columns = new ColumnType[] {
        new AtomicColumnType(),
        new KeyValuedColumnType()
    };


    public ColumnType() {

    }

    public ColumnType(BaseType baseType) {
        this.baseType = baseType;
    }

    public BaseType getBaseType() {
        return baseType;
    }

    /**
     * JSON.
     * <pre>
            "type": {
                "key": {
                     "maxInteger": 4294967295,
                     "minInteger": 0,
                     "type": "integer"
                },
                "min": 0,
                "value": {
                    "type": "uuid",
                    "refTable": "Queue"
                 },
                 "max": "unlimited"
            }</pre>
     */
    public static ColumnType fromJson(JsonNode json) {
        for (ColumnType colType : columns) {
            ColumnType columnType = colType.fromJsonNode(json);
            if (null != columnType) {
                return columnType;
            }
        }
        //todo move to speicfic typed exception
        throw new TyperException(String.format("could not find the right column type %s",
                JsonUtils.prettyString(json)));
    }


    /**
     * Creates a ColumnType from the JsonNode if the implementation  knows how to, returns null otherwise.
     *
     * @param json the JSONNode object that needs to converted
     * @return a valid SubType or Null (if the JsonNode does not represent the subtype)
     */
    protected abstract ColumnType fromJsonNode(JsonNode json);

    /*
     * Per RFC 7047, Section 3.2 <type> :
     * If "min" or "max" is not specified, each defaults to 1.  If "max" is specified as "unlimited",
     * then there is no specified maximum number of elements, although the implementation will
     * enforce some limit.  After considering defaults, "min" must be exactly 0 or
     * exactly 1, "max" must be at least 1, and "max" must be greater than or equal to "min".
     *
     * If "min" and "max" are both 1 and "value" is not specified, the
     * type is the scalar type specified by "key".
     */
    public boolean isMultiValued() {
        return this.min != this.max;
    }

    public abstract Object valueFromJson(JsonNode value);

    public abstract void validate(Object value);

    @Override
    public String toString() {
        return "ColumnType{"
                + "baseType=" + baseType
                + ", min=" + min
                + ", max=" + max
                + '}';
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result
                + ((baseType == null) ? 0 : baseType.hashCode());
        result = prime * result + (int) (max ^ (max >>> 32));
        result = prime * result + (int) (min ^ (min >>> 32));
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        ColumnType other = (ColumnType) obj;
        if (baseType == null) {
            if (other.baseType != null) {
                return false;
            }
        } else if (!baseType.equals(other.baseType)) {
            return false;
        }
        if (max != other.max) {
            return false;
        }
        if (min != other.min) {
            return false;
        }
        return true;
    }

    public static class AtomicColumnType extends ColumnType {
        public AtomicColumnType() {
        }

        public AtomicColumnType(BaseType baseType) {
            super(baseType);
        }

        @Override
        public AtomicColumnType fromJsonNode(JsonNode json) {
            if (json.isObject() && json.has("value")) {
                return null;
            }
            BaseType jsonBaseType = BaseType.fromJson(json, "key");

            if (jsonBaseType != null) {

                AtomicColumnType atomicColumnType = new AtomicColumnType(jsonBaseType);

                JsonNode minNode = json.get("min");
                if (minNode != null) {
                    atomicColumnType.setMin(minNode.asLong());
                }

                JsonNode maxNode = json.get("max");
                if (maxNode != null) {
                    if (maxNode.isNumber()) {
                        atomicColumnType.setMax(maxNode.asLong());
                    } else if ("unlimited".equals(maxNode.asText())) {
                        atomicColumnType.setMax(Long.MAX_VALUE);
                    }
                }
                return atomicColumnType;
            }

            return null;
        }

        @Override
        public Object valueFromJson(JsonNode value) {
            if (isMultiValued()) {
                OvsdbSet<Object> result = new OvsdbSet<>();
                if (value.isArray()) {
                    if (value.size() == 2) {
                        if (value.get(0).isTextual() && "set".equals(value.get(0).asText())) {
                            for (JsonNode node: value.get(1)) {
                                result.add(getBaseType().toValue(node));
                            }
                        } else {
                            result.add(getBaseType().toValue(value));
                        }
                    }
                } else {
                    result.add(getBaseType().toValue(value));
                }
                return result;
            } else {
                return getBaseType().toValue(value);
            }
        }

        @Override
        public void validate(Object value) {
            this.baseType.validate(value);
        }

    }

    public static class KeyValuedColumnType extends ColumnType {
        BaseType keyType;

        public BaseType getKeyType() {
            return keyType;
        }

        public KeyValuedColumnType() {
        }

        public KeyValuedColumnType(BaseType keyType, BaseType valueType) {
            super(valueType);
            this.keyType = keyType;
        }

        @Override
        public KeyValuedColumnType fromJsonNode(JsonNode json) {
            if (json.isValueNode() || !json.has("value")) {
                return null;
            }
            BaseType jsonKeyType = BaseType.fromJson(json, "key");
            BaseType valueType = BaseType.fromJson(json, "value");

            KeyValuedColumnType keyValueColumnType = new KeyValuedColumnType(jsonKeyType, valueType);
            JsonNode minNode = json.get("min");
            if (minNode != null) {
                keyValueColumnType.setMin(minNode.asLong());
            }

            JsonNode maxNode = json.get("max");
            if (maxNode != null) {
                if (maxNode.isLong()) {
                    keyValueColumnType.setMax(maxNode.asLong());
                } else if (maxNode.isTextual() && "unlimited".equals(maxNode.asText())) {
                    keyValueColumnType.setMax(Long.MAX_VALUE);
                }
            }

            return keyValueColumnType;
        }

        @Override
        public Object valueFromJson(JsonNode node) {
            if (node.isArray() && node.size() == 2) {
                if (node.get(0).isTextual() && "map".equals(node.get(0).asText())) {
                    OvsdbMap<Object, Object> map = new OvsdbMap<>();
                    for (JsonNode pairNode : node.get(1)) {
                        if (pairNode.isArray() && node.size() == 2) {
                            Object key = getKeyType().toValue(pairNode.get(0));
                            Object value = getBaseType().toValue(pairNode.get(1));
                            map.put(key, value);
                        }
                    }
                    return map;
                } else if (node.size() == 0) {
                    return null;
                }
            }
            return null;
        }

        @Override
        public void validate(Object value) {
            this.baseType.validate(value);
        }

        @Override
        public String toString() {
            return "KeyValuedColumnType [keyType=" + keyType + " " + super.toString() + "]";
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = super.hashCode();
            result = prime * result
                    + ((keyType == null) ? 0 : keyType.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!super.equals(obj)) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            KeyValuedColumnType other = (KeyValuedColumnType) obj;
            if (keyType == null) {
                if (other.keyType != null) {
                    return false;
                }
            } else if (!keyType.equals(other.keyType)) {
                return false;
            }
            return true;
        }
    }
}
