/*
 * Copyright (c) 2020 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */

package org.truffleruby.core.array.library;

import java.util.Iterator;
import java.util.NoSuchElementException;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import org.truffleruby.core.array.ArrayGuards;
import org.truffleruby.core.array.library.ArrayStoreLibrary.ArrayAllocator;

@ExportLibrary(value = ArrayStoreLibrary.class)
@GenerateUncached
public class ZeroLengthArrayStore {

    private ZeroLengthArrayStore() {
    }

    public static final ZeroLengthArrayStore ZERO_LENGTH_STORE = new ZeroLengthArrayStore();

    @ExportMessage
    @TruffleBoundary
    public Object read(int index) {
        throw new UnsupportedOperationException();
    }

    @ExportMessage
    public boolean isPrimitive() {
        return true;
    }

    @ExportMessage
    public static String toString(ZeroLengthArrayStore store) {
        return "empty";
    }

    @ExportMessage
    public int capacity() {
        return 0;
    }

    @ExportMessage
    public Object[] expand(int newCapacity) {
        return new Object[newCapacity];
    }

    @ExportMessage
    public Object extractRange(int start, int end) {
        assert start == 0;
        assert end == 0;
        return this;
    }

    @ExportMessage
    public Object[] boxedCopyOfRange(int start, int length) {
        assert start == 0;
        assert length == 0;

        return new Object[0];
    }

    @ExportMessage
    public void copyContents(int srcStart, Object destStore, int destStart, int length) {
        assert srcStart == 0;
        assert length == 0;
    }

    @ExportMessage
    public Object[] toJavaArrayCopy(int length) {
        assert length == 0;
        return new Object[length];
    }

    @ExportMessage
    public void sort(int size) {
        assert size == 0;
    }

    @ExportMessage
    public Iterable<Object> getIterable(int from, int length) {
        return () -> new Iterator<Object>() {
            @Override
            public boolean hasNext() {
                return false;
            }

            @Override
            public Object next() throws NoSuchElementException {
                throw new NoSuchElementException();
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException("remove");
            }

        };
    }

    @ExportMessage
    static class GeneralizeForValue {

        @Specialization
        protected static ArrayAllocator generalize(ZeroLengthArrayStore store, int newValue) {
            return IntegerArrayStore.INTEGER_ARRAY_ALLOCATOR;
        }

        @Specialization
        protected static ArrayAllocator generalize(ZeroLengthArrayStore store, long newValue) {
            return LongArrayStore.LONG_ARRAY_ALLOCATOR;
        }

        @Specialization
        protected static ArrayAllocator generalize(ZeroLengthArrayStore store, double newValue) {
            return DoubleArrayStore.DOUBLE_ARRAY_ALLOCATOR;
        }

        @Fallback
        protected static ArrayAllocator generalize(ZeroLengthArrayStore store, Object newValue) {
            return ObjectArrayStore.OBJECT_ARRAY_ALLOCATOR;
        }
    }

    @ExportMessage
    @ImportStatic(ArrayGuards.class)
    static class GeneralizeForStore {

        @Specialization
        protected static ArrayAllocator generalize(ZeroLengthArrayStore store, ZeroLengthArrayStore newStore) {
            return ZERO_LENGTH_ALLOCATOR;
        }

        @Specialization(limit = "STORAGE_STRATEGIES")
        protected static ArrayAllocator generalize(ZeroLengthArrayStore store, Object newStore,
                @CachedLibrary("newStore") ArrayStoreLibrary newStores) {
            return newStores.allocator(newStore);
        }
    }

    @ExportMessage
    public static ArrayAllocator allocator(ZeroLengthArrayStore store) {
        return ZERO_LENGTH_ALLOCATOR;
    }

    static final ArrayAllocator ZERO_LENGTH_ALLOCATOR = new ZeroLengthAllocator();

    static class ZeroLengthAllocator extends ArrayAllocator {

        @Override
        public Object allocate(int capacity) {
            assert capacity == 0;
            return ZERO_LENGTH_STORE;
        }

        @Override
        public boolean accepts(Object value) {
            return false;
        }

        @Override
        public boolean specializesFor(Object value) {
            return false;
        }

        @Override
        public boolean isDefaultValue(Object value) {
            return value == null;
        }
    }
}
