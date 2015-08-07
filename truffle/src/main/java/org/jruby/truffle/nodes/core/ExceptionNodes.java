/*
 * Copyright (c) 2013, 2015 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.jruby.truffle.nodes.core;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.source.SourceSection;
import org.jruby.truffle.nodes.RubyGuards;
import org.jruby.truffle.nodes.core.array.ArrayNodes;
import org.jruby.truffle.nodes.objects.Allocator;
import org.jruby.truffle.nodes.objectstorage.ReadHeadObjectFieldNode;
import org.jruby.truffle.runtime.NotProvided;
import org.jruby.truffle.runtime.RubyCallStack;
import org.jruby.truffle.runtime.RubyContext;
import org.jruby.truffle.runtime.backtrace.Activation;
import org.jruby.truffle.runtime.backtrace.Backtrace;
import org.jruby.truffle.runtime.backtrace.MRIBacktraceFormatter;
import org.jruby.truffle.runtime.core.RubyBasicObject;
import org.jruby.truffle.runtime.core.RubyException;

import java.util.List;

@CoreClass(name = "Exception")
public abstract class ExceptionNodes {

    public static class BacktraceFormatter extends MRIBacktraceFormatter {
        @Override
        protected String formatFromLine(List<Activation> activations, int n) {
            return formatCallerLine(activations, n);
        }
    }

    public static final BacktraceFormatter BACKTRACE_FORMATTER = new BacktraceFormatter();

    // TODO (eregon 16 Apr. 2015): MRI does a dynamic calls to "message"
    public static Object getMessage(RubyBasicObject exception) {
        assert RubyGuards.isRubyException(exception);
        return ((RubyException) exception).message;
    }

    public static Backtrace getBacktrace(RubyBasicObject exception) {
        assert RubyGuards.isRubyException(exception);
        return ((RubyException) exception).backtrace;
    }

    public static void setBacktrace(RubyBasicObject exception, Backtrace backtrace) {
        assert RubyGuards.isRubyException(exception);
        ((RubyException) exception).backtrace = backtrace;
    }

    public static RubyBasicObject asRubyStringArray(RubyBasicObject exception) {
        assert RubyGuards.isRubyException(exception);

        assert getBacktrace(exception) != null;
        final String[] lines = BACKTRACE_FORMATTER.format(exception.getContext(), exception, getBacktrace(exception));

        final Object[] array = new Object[lines.length];

        for (int n = 0;n < lines.length; n++) {
            array[n] = StringNodes.createString(exception.getContext().getCoreLibrary().getStringClass(), lines[n]);
        }

        return ArrayNodes.fromObjects(exception.getContext().getCoreLibrary().getArrayClass(), array);
    }

    public static void setMessage(RubyBasicObject exception, Object message) {
        assert RubyGuards.isRubyException(exception);
        ((RubyException) exception).message = message;
    }

    public static RubyBasicObject createRubyException(RubyBasicObject rubyClass) {
        return new RubyException(rubyClass);
    }

    public static RubyBasicObject createRubyException(RubyBasicObject rubyClass, Object message, Backtrace backtrace) {
        return new RubyException(rubyClass, message, backtrace);
    }

    @CoreMethod(names = "initialize", optional = 1)
    public abstract static class InitializeNode extends CoreMethodArrayArgumentsNode {

        public InitializeNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @Specialization
        public RubyBasicObject initialize(RubyBasicObject exception, NotProvided message) {
            setMessage(exception, nil());
            return exception;
        }

        @Specialization(guards = "wasProvided(message)")
        public RubyBasicObject initialize(RubyBasicObject exception, Object message) {
            setMessage(exception, message);
            return exception;
        }

    }

    @CoreMethod(names = "backtrace")
    public abstract static class BacktraceNode extends CoreMethodArrayArgumentsNode {

        @Child ReadHeadObjectFieldNode readCustomBacktrace;

        public BacktraceNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
            readCustomBacktrace = new ReadHeadObjectFieldNode("@custom_backtrace");
        }

        @TruffleBoundary
        @Specialization
        public Object backtrace(RubyBasicObject exception) {
            if (readCustomBacktrace.isSet(exception)) {
                return readCustomBacktrace.execute(exception);
            } else if (getBacktrace(exception) != null) {
                return asRubyStringArray(exception);
            } else {
                return nil();
            }
        }

    }

    @RubiniusOnly
    @CoreMethod(names = "capture_backtrace!", optional = 1)
    public abstract static class CaptureBacktraceNode extends CoreMethodArrayArgumentsNode {

        public CaptureBacktraceNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @Specialization
        public RubyBasicObject captureBacktrace(RubyBasicObject exception, NotProvided offset) {
            return captureBacktrace(exception, 1);
        }

        @Specialization
        public RubyBasicObject captureBacktrace(RubyBasicObject exception, int offset) {
            Backtrace backtrace = RubyCallStack.getBacktrace(this, offset);
            setBacktrace(exception, backtrace);
            return nil();
        }

    }

    @CoreMethod(names = "message")
    public abstract static class MessageNode extends CoreMethodArrayArgumentsNode {

        public MessageNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @Specialization
        public Object message(RubyBasicObject exception) {
            return getMessage(exception);
        }

    }

    public static class ExceptionAllocator implements Allocator {

        @Override
        public RubyBasicObject allocate(RubyContext context, RubyBasicObject rubyClass, Node currentNode) {
            return createRubyException(rubyClass);
        }

    }
}
