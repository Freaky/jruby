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

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.source.SourceSection;
import org.jruby.RubyThread.Status;
import org.jruby.runtime.Visibility;
import org.jruby.truffle.nodes.RubyGuards;
import org.jruby.truffle.nodes.dispatch.CallDispatchHeadNode;
import org.jruby.truffle.nodes.dispatch.DispatchHeadNodeFactory;
import org.jruby.truffle.nodes.objects.Allocator;
import org.jruby.truffle.runtime.NotProvided;
import org.jruby.truffle.runtime.RubyContext;
import org.jruby.truffle.runtime.control.RaiseException;
import org.jruby.truffle.runtime.control.ReturnException;
import org.jruby.truffle.runtime.control.ThreadExitException;
import org.jruby.truffle.runtime.core.RubyBasicObject;
import org.jruby.truffle.runtime.core.RubyClass;
import org.jruby.truffle.runtime.core.RubyFiber;
import org.jruby.truffle.runtime.core.RubyThread;
import org.jruby.truffle.runtime.subsystems.FiberManager;
import org.jruby.truffle.runtime.subsystems.SafepointAction;
import org.jruby.truffle.runtime.subsystems.ThreadManager;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

@CoreClass(name = "Thread")
public abstract class ThreadNodes {

    public static RubyBasicObject createRubyThread(RubyBasicObject rubyClass, ThreadManager manager) {
        return new RubyThread(rubyClass, manager);
    }

    public static void initialize(final RubyBasicObject thread, RubyContext context, Node currentNode, final Object[] arguments, final RubyBasicObject block) {
        assert RubyGuards.isRubyThread(thread);
        assert RubyGuards.isRubyProc(block);
        String info = ProcNodes.getSharedMethodInfo(block).getSourceSection().getShortDescription();
        initialize(thread, context, currentNode, info, new Runnable() {
            @Override
            public void run() {
                getFields(((RubyThread) thread)).value = ProcNodes.rootCall(block, arguments);
            }
        });
    }

    public static void initialize(final RubyBasicObject thread, final RubyContext context, final Node currentNode, final String info, final Runnable task) {
        assert RubyGuards.isRubyThread(thread);
        new Thread(new Runnable() {
            @Override
            public void run() {
                ThreadNodes.run(thread, context, currentNode, info, task);
            }
        }).start();
    }

    public static void run(RubyBasicObject thread, final RubyContext context, Node currentNode, String info, Runnable task) {
        assert RubyGuards.isRubyThread(thread);

        getFields(((RubyThread) thread)).name = "Ruby Thread@" + info;
        Thread.currentThread().setName(getFields(((RubyThread) thread)).name);

        start(thread);
        try {
            RubyBasicObject fiber = getRootFiber(thread);
            FiberNodes.run(fiber, task);
        } catch (ThreadExitException e) {
            getFields(((RubyThread) thread)).value = context.getCoreLibrary().getNilObject();
            return;
        } catch (RaiseException e) {
            getFields(((RubyThread) thread)).exception = e.getRubyException();
        } catch (ReturnException e) {
            getFields(((RubyThread) thread)).exception = context.getCoreLibrary().unexpectedReturn(currentNode);
        } finally {
            cleanup(thread);
        }
    }

    // Only used by the main thread which cannot easily wrap everything inside a try/finally.
    public static void start(RubyBasicObject thread) {
        assert RubyGuards.isRubyThread(thread);
        getFields(((RubyThread) thread)).thread = Thread.currentThread();
        getFields(((RubyThread) thread)).manager.registerThread(thread);
    }

    // Only used by the main thread which cannot easily wrap everything inside a try/finally.
    public static void cleanup(RubyBasicObject thread) {
        assert RubyGuards.isRubyThread(thread);

        getFields(((RubyThread) thread)).status = Status.ABORTING;
        getFields(((RubyThread) thread)).manager.unregisterThread(thread);

        getFields(((RubyThread) thread)).status = Status.DEAD;
        getFields(((RubyThread) thread)).thread = null;
        releaseOwnedLocks(thread);
        getFields(((RubyThread) thread)).finished.countDown();
    }

    public static void shutdown(RubyBasicObject thread) {
        assert RubyGuards.isRubyThread(thread);
        getFields(((RubyThread) thread)).fiberManager.shutdown();
        throw new ThreadExitException();
    }

    public static Thread getRootFiberJavaThread(RubyBasicObject thread) {
        assert RubyGuards.isRubyThread(thread);
        return getFields(((RubyThread) thread)).thread;
    }

    public static Thread getCurrentFiberJavaThread(RubyBasicObject thread) {
        assert RubyGuards.isRubyThread(thread);
        return FiberNodes.getFields(((RubyFiber) getFields(((RubyThread) thread)).fiberManager.getCurrentFiber())).thread;
    }

    public static void join(final RubyBasicObject thread) {
        assert RubyGuards.isRubyThread(thread);
        getFields(((RubyThread) thread)).manager.runUntilResult(new ThreadManager.BlockingAction<Boolean>() {
            @Override
            public Boolean block() throws InterruptedException {
                getFields(((RubyThread) thread)).finished.await();
                return SUCCESS;
            }
        });

        if (getFields(((RubyThread) thread)).exception != null) {
            throw new RaiseException(getFields(((RubyThread) thread)).exception);
        }
    }

    public static boolean join(final RubyBasicObject thread, final int timeoutInMillis) {
        assert RubyGuards.isRubyThread(thread);
        final long start = System.currentTimeMillis();
        final boolean joined = getFields(((RubyThread) thread)).manager.runUntilResult(new ThreadManager.BlockingAction<Boolean>() {
            @Override
            public Boolean block() throws InterruptedException {
                long now = System.currentTimeMillis();
                long waited = now - start;
                if (waited >= timeoutInMillis) {
                    // We need to know whether countDown() was called and we do not want to block.
                    return getFields(((RubyThread) thread)).finished.getCount() == 0;
                }
                return getFields(((RubyThread) thread)).finished.await(timeoutInMillis - waited, TimeUnit.MILLISECONDS);
            }
        });

        if (joined && getFields(((RubyThread) thread)).exception != null) {
            throw new RaiseException(getFields(((RubyThread) thread)).exception);
        }

        return joined;
    }

    public static void wakeup(RubyBasicObject thread) {
        assert RubyGuards.isRubyThread(thread);
        getFields(((RubyThread) thread)).wakeUp.set(true);
        Thread t = getFields(((RubyThread) thread)).thread;
        if (t != null) {
            t.interrupt();
        }
    }

    public static Object getValue(RubyBasicObject thread) {
        assert RubyGuards.isRubyThread(thread);
        return getFields(((RubyThread) thread)).value;
    }

    public static Object getException(RubyBasicObject thread) {
        assert RubyGuards.isRubyThread(thread);
        return getFields(((RubyThread) thread)).exception;
    }

    public static String getName(RubyBasicObject thread) {
        assert RubyGuards.isRubyThread(thread);
        return getFields(((RubyThread) thread)).name;
    }

    public static void setName(RubyBasicObject thread, String name) {
        assert RubyGuards.isRubyThread(thread);
        getFields(((RubyThread) thread)).name = name;
    }

    public static ThreadManager getThreadManager(RubyBasicObject thread) {
        assert RubyGuards.isRubyThread(thread);
        return getFields(((RubyThread) thread)).manager;
    }

    public static FiberManager getFiberManager(RubyBasicObject thread) {
        assert RubyGuards.isRubyThread(thread);
        return getFields(((RubyThread) thread)).fiberManager;
    }

    public static RubyBasicObject getRootFiber(RubyBasicObject thread) {
        assert RubyGuards.isRubyThread(thread);
        return getFields(((RubyThread) thread)).fiberManager.getRootFiber();
    }

    public static boolean isAbortOnException(RubyBasicObject thread) {
        assert RubyGuards.isRubyThread(thread);
        return getFields(((RubyThread) thread)).abortOnException;
    }

    public static void setAbortOnException(RubyBasicObject thread, boolean abortOnException) {
        assert RubyGuards.isRubyThread(thread);
        getFields(((RubyThread) thread)).abortOnException = abortOnException;
    }

    public static InterruptMode getInterruptMode(RubyBasicObject thread) {
        assert RubyGuards.isRubyThread(thread);
        return getFields(((RubyThread) thread)).interruptMode;
    }

    public static void setInterruptMode(RubyBasicObject thread, InterruptMode interruptMode) {
        assert RubyGuards.isRubyThread(thread);
        getFields(((RubyThread) thread)).interruptMode = interruptMode;
    }

    /** Return whether Thread#{run,wakeup} was called and clears the wakeup flag.
     * @param thread*/
    public static boolean shouldWakeUp(RubyBasicObject thread) {
        assert RubyGuards.isRubyThread(thread);
        return getFields(((RubyThread) thread)).wakeUp.getAndSet(false);
    }

    public static void acquiredLock(RubyBasicObject thread, Lock lock) {
        assert RubyGuards.isRubyThread(thread);
        getFields(((RubyThread) thread)).ownedLocks.add(lock);
    }

    public static void releasedLock(RubyBasicObject thread, Lock lock) {
        assert RubyGuards.isRubyThread(thread);
        // TODO: this is O(ownedLocks.length).
        getFields(((RubyThread) thread)).ownedLocks.remove(lock);
    }

    public static void releaseOwnedLocks(RubyBasicObject thread) {
        assert RubyGuards.isRubyThread(thread);
        for (Lock lock : getFields(((RubyThread) thread)).ownedLocks) {
            lock.unlock();
        }
    }

    public static Status getStatus(RubyBasicObject thread) {
        assert RubyGuards.isRubyThread(thread);
        return getFields(((RubyThread) thread)).status;
    }

    public static void setStatus(RubyBasicObject thread, Status status) {
        assert RubyGuards.isRubyThread(thread);
        getFields(((RubyThread) thread)).status = status;
    }

    public static RubyBasicObject getThreadLocals(RubyBasicObject thread) {
        assert RubyGuards.isRubyThread(thread);
        return getFields(((RubyThread) thread)).threadLocals;
    }

    public static RubyThread.ThreadFields getFields(RubyThread thread) {
        return thread.fields;
    }

    public static RubyThread.ThreadFields getFields(RubyBasicObject thread) {
        assert RubyGuards.isRubyThread(thread);
        return ((RubyThread) thread).fields;
    }

    public enum InterruptMode {
        IMMEDIATE, ON_BLOCKING, NEVER
    }

    @CoreMethod(names = "alive?")
    public abstract static class AliveNode extends CoreMethodArrayArgumentsNode {

        public AliveNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @Specialization
        public boolean alive(RubyBasicObject thread) {
            return getStatus(thread) != Status.ABORTING && getStatus(thread) != Status.DEAD;
        }

    }

    @CoreMethod(names = "current", onSingleton = true)
    public abstract static class CurrentNode extends CoreMethodArrayArgumentsNode {

        public CurrentNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @Specialization
        public RubyBasicObject current() {
            return getContext().getThreadManager().getCurrentThread();
        }

    }

    @CoreMethod(names = { "kill", "exit", "terminate" })
    public abstract static class KillNode extends CoreMethodArrayArgumentsNode {

        public KillNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @Specialization
        public RubyBasicObject kill(final RubyBasicObject rubyThread) {
            final Thread toKill = getRootFiberJavaThread(rubyThread);

            getContext().getSafepointManager().pauseThreadAndExecuteLater(toKill, this, new SafepointAction() {
                @Override
                public void run(RubyBasicObject currentThread, Node currentNode) {
                    shutdown(currentThread);
                }
            });

            return rubyThread;
        }

    }

    @RubiniusOnly
    @CoreMethod(names = "handle_interrupt", required = 2, needsBlock = true, visibility = Visibility.PRIVATE)
    public abstract static class HandleInterruptNode extends YieldingCoreMethodNode {

        private final RubyBasicObject immediateSymbol = getContext().getSymbol("immediate");
        private final RubyBasicObject onBlockingSymbol = getContext().getSymbol("on_blocking");
        private final RubyBasicObject neverSymbol = getContext().getSymbol("never");

        public HandleInterruptNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @Specialization(guards = {"isRubyClass(exceptionClass)", "isRubySymbol(timing)", "isRubyProc(block)"})
        public Object handle_interrupt(VirtualFrame frame, RubyBasicObject self, RubyBasicObject exceptionClass, RubyBasicObject timing, RubyBasicObject block) {
            // TODO (eregon, 12 July 2015): should we consider exceptionClass?
            final InterruptMode newInterruptMode = symbolToInterruptMode(timing);

            final InterruptMode oldInterruptMode = getInterruptMode(self);
            setInterruptMode(self, newInterruptMode);
            try {
                return yield(frame, block);
            } finally {
                setInterruptMode(self, oldInterruptMode);
            }
        }

        private InterruptMode symbolToInterruptMode(RubyBasicObject symbol) {
            if (symbol == immediateSymbol) {
                return InterruptMode.IMMEDIATE;
            } else if (symbol == onBlockingSymbol) {
                return InterruptMode.ON_BLOCKING;
            } else if (symbol == neverSymbol) {
                return InterruptMode.NEVER;
            } else {
                CompilerDirectives.transferToInterpreter();
                throw new RaiseException(getContext().getCoreLibrary().argumentError("invalid timing symbol", this));
            }
        }

    }

    @CoreMethod(names = "initialize", rest = true, needsBlock = true)
    public abstract static class InitializeNode extends CoreMethodArrayArgumentsNode {

        public InitializeNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @TruffleBoundary
        @Specialization(guards = "isRubyProc(block)")
        public RubyBasicObject initialize(RubyBasicObject thread, Object[] arguments, RubyBasicObject block) {
            ThreadNodes.initialize(thread, getContext(), this, arguments, block);
            return nil();
        }

    }

    @CoreMethod(names = "join", optional = 1)
    public abstract static class JoinNode extends CoreMethodArrayArgumentsNode {

        public JoinNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @Specialization
        public RubyBasicObject join(RubyBasicObject thread, NotProvided timeout) {
            ThreadNodes.join(thread);
            return thread;
        }

        @Specialization(guards = "isNil(nil)")
        public RubyBasicObject join(RubyBasicObject thread, Object nil) {
            return join(thread, NotProvided.INSTANCE);
        }

        @Specialization
        public Object join(RubyBasicObject thread, int timeout) {
            return joinMillis(thread, timeout * 1000);
        }

        @Specialization
        public Object join(RubyBasicObject thread, double timeout) {
            return joinMillis(thread, (int) (timeout * 1000.0));
        }

        private Object joinMillis(RubyBasicObject self, int timeoutInMillis) {
            assert RubyGuards.isRubyThread(self);

            if (ThreadNodes.join(self, timeoutInMillis)) {
                return self;
            } else {
                return nil();
            }
        }

    }

    @CoreMethod(names = "main", onSingleton = true)
    public abstract static class MainNode extends CoreMethodArrayArgumentsNode {

        public MainNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @Specialization
        public RubyBasicObject main() {
            return getContext().getThreadManager().getRootThread();
        }

    }

    @CoreMethod(names = "pass", onSingleton = true)
    public abstract static class PassNode extends CoreMethodArrayArgumentsNode {

        public PassNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @Specialization
        public RubyBasicObject pass(VirtualFrame frame) {
            Thread.yield();
            return nil();
        }

    }

    @CoreMethod(names = "status")
    public abstract static class StatusNode extends CoreMethodArrayArgumentsNode {

        public StatusNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @Specialization
        public Object status(RubyBasicObject self) {
            // TODO: slightly hackish
            if (getStatus(self) == Status.DEAD) {
                if (getException(self) != null) {
                    return nil();
                } else {
                    return false;
                }
            }

            return createString(getStatus(self).bytes);
        }

    }

    @CoreMethod(names = "stop?")
    public abstract static class StopNode extends CoreMethodArrayArgumentsNode {

        public StopNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @Specialization
        public boolean stop(RubyBasicObject self) {
            return getStatus(self) == Status.DEAD || getStatus(self) == Status.SLEEP;
        }

    }

    @CoreMethod(names = "value")
    public abstract static class ValueNode extends CoreMethodArrayArgumentsNode {

        public ValueNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @Specialization
        public Object value(RubyBasicObject self) {
            join(self);
            return getValue(self);
        }

    }

    @CoreMethod(names = { "wakeup", "run" })
    public abstract static class WakeupNode extends CoreMethodArrayArgumentsNode {

        public WakeupNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @Specialization
        public RubyBasicObject wakeup(final RubyBasicObject thread) {
            if (getStatus(thread) == Status.DEAD) {
                CompilerDirectives.transferToInterpreter();
                throw new RaiseException(getContext().getCoreLibrary().threadError("killed thread", this));
            }

            // TODO: should only interrupt sleep
            ThreadNodes.wakeup(thread);

            return thread;
        }

    }

    @CoreMethod(names = "abort_on_exception")
    public abstract static class AbortOnExceptionNode extends CoreMethodArrayArgumentsNode {

        public AbortOnExceptionNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @Specialization
        public boolean abortOnException(RubyBasicObject self) {
            return isAbortOnException(self);
        }

    }

    @CoreMethod(names = "abort_on_exception=", required = 1)
    public abstract static class SetAbortOnExceptionNode extends CoreMethodArrayArgumentsNode {

        public SetAbortOnExceptionNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @Specialization
        public RubyBasicObject setAbortOnException(RubyBasicObject self, boolean abortOnException) {
            ThreadNodes.setAbortOnException(self, abortOnException);
            return nil();
        }

    }

    public static class ThreadAllocator implements Allocator {

        @Override
        public RubyBasicObject allocate(RubyContext context, RubyBasicObject rubyClass, Node currentNode) {
            return createRubyThread(rubyClass, context.getThreadManager());
        }

    }

}
