/*
 * Copyright (c) 2019 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.interop.messages;

import org.truffleruby.Layouts;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.object.DynamicObject;

@ExportLibrary(value = InteropLibrary.class, receiverType = DynamicObject.class)
public class PointerMessages extends RubyObjectMessages {

    @ExportMessage
    public static boolean isPointer(DynamicObject receiver) {
        return true;
    }

    @ExportMessage
    public static long asPointer(DynamicObject receiver) {
        return Layouts.POINTER.getPointer(receiver).getAddress();
    }

    @ExportMessage
    public static void toNative(DynamicObject receiver) {
    }
}
