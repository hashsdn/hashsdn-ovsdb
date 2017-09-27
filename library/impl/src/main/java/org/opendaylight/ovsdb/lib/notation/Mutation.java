/*
 * Copyright (c) 2013, 2015 Red Hat, Inc. and others. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.ovsdb.lib.notation;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.opendaylight.ovsdb.lib.notation.json.MutationSerializer;

@JsonSerialize(using = MutationSerializer.class)
public class Mutation {
    String column;
    Mutator mutator;
    Object value;

    public Mutation(String column, Mutator mutator, Object value) {
        this.column = column;
        this.mutator = mutator;
        this.value = value;
    }

    public String getColumn() {
        return column;
    }

    public void setColumn(String column) {
        this.column = column;
    }

    public Mutator getMutator() {
        return mutator;
    }

    public void setMutator(Mutator mutator) {
        this.mutator = mutator;
    }

    public Object getValue() {
        return value;
    }

    public void setValue(Object value) {
        this.value = value;
    }
}
