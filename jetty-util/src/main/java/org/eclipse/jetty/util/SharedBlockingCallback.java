//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.util;

import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Provides a reusable {@link Callback} that can block the thread
 * while waiting to be completed.
 * <p>
 * A typical usage pattern is:
 * <pre>
 * void someBlockingCall(Object... args) throws IOException
 * {
 *     try(Blocker blocker = sharedBlockingCallback.acquire())
 *     {
 *         someAsyncCall(args, blocker);
 *         blocker.block();
 *     }
 * }
 * </pre>
 */
public class SharedBlockingCallback
{
    static final Logger LOG = Log.getLogger(SharedBlockingCallback.class);

    private static Throwable IDLE = new ConstantThrowable("IDLE");
    private static Throwable SUCCEEDED = new ConstantThrowable("SUCCEEDED");
    private static Throwable FAILED = new ConstantThrowable("FAILED");

    private final ReentrantLock _lock = new ReentrantLock();
    private final Condition _idle = _lock.newCondition();
    private final Condition _complete = _lock.newCondition();
    private Blocker _blocker = new Blocker();

    protected long getBlockingTimeout()
    {
        return -1;
    }

    public Blocker acquire() throws IOException
    {
        _lock.lock();
        try
        {
            // TODO review the need to wait here. It was added to support
            // multiple websocket writers, but other implementations tend just
            // to throw a Write Pending exception. See 430242
            while (_blocker._state != IDLE)
            {
                if (_blocker._state instanceof BlockerTimeoutException)
                    throw new IOException(_blocker._state);
                
                // We can wait forever here as the other usage of this SBCB will
                // must eventually close (or timeout is configured infinite anyway)
                _idle.await();
            }
            _blocker._state = null;
            return _blocker;
        }
        catch (InterruptedException x)
        {
            throw new InterruptedIOException();
        }
        finally
        {
            _lock.unlock();
        }
    }

    protected void notComplete(Blocker blocker)
    {
        LOG.warn("Blocker not complete {}",blocker);
        if (LOG.isDebugEnabled())
            LOG.debug(new Throwable());
    }

    protected void preBlock()
    {
    }

    protected void postBlock()
    {
    }
    
    /**
     * A Closeable Callback.
     * Uses the auto close mechanism to check block has been called OK.
     * <p>Implements {@link Callback} because calls to this
     * callback do not blocak, rather they wakeup the thread that is blocked
     * in {@link #block()}
     */
    public class Blocker implements Callback, Closeable
    {
        private Throwable _state = IDLE;
        
        protected Blocker()
        {
        }

        @Override
        public InvocationType getInvocationType()
        {
            return InvocationType.NON_BLOCKING;
        }
        
        @Override
        public void succeeded()
        {
            _lock.lock();
            try
            {
                if (_state == null)
                {
                    _state = SUCCEEDED;
                    _complete.signalAll();
                }
                else
                    throw new IllegalStateException(_state);
            }
            finally
            {
                _lock.unlock();
            }
        }

        @Override
        public void failed(Throwable cause)
        {
            _lock.lock();
            try
            {
                if (_state == null)
                {
                    if (cause==null)
                        _state=FAILED;
                    else if (cause instanceof BlockerTimeoutException)
                        // Not this blockers timeout
                        _state=new IOException(cause);
                    else 
                        _state=cause;
                    _complete.signalAll();
                }
                else if (_state instanceof BlockerTimeoutException)
                {
                    // Failure arrived late, block() already
                    // modified the state, nothing more to do.
                }
                else
                {
                    cause.printStackTrace(System.err);
                    throw new IllegalStateException(_state);
                }
            }
            finally
            {
                _lock.unlock();
            }
        }

        /**
         * Block until the Callback has succeeded or failed and after the return leave in the state to allow reuse. This is useful for code that wants to
         * repeatable use a FutureCallback to convert an asynchronous API to a blocking API.
         * 
         * @throws IOException
         *             if exception was caught during blocking, or callback was cancelled
         */
        public void block() throws IOException
        {
            long idle = getBlockingTimeout();
            _lock.lock();
            try
            {
                preBlock();
                while (_state == null)
                {
                    if (idle >= 0)
                    {
                        if (!_complete.await(idle, TimeUnit.MILLISECONDS))
                        {
                            // Method failed(Throwable) has not been called yet,
                            // so we will synthesize a special TimeoutException.
                            _state = new BlockerTimeoutException();
                        }
                    }
                    else
                    {
                        _complete.await();
                    }
                }

                if (_state == SUCCEEDED)
                    return;
                if (_state == IDLE)
                    throw new IllegalStateException("IDLE");
                if (_state instanceof IOException)
                    throw (IOException)_state;
                if (_state instanceof CancellationException)
                    throw (CancellationException)_state;
                if (_state instanceof RuntimeException)
                    throw (RuntimeException)_state;
                if (_state instanceof Error)
                    throw (Error)_state;
                throw new IOException(_state);
            }
            catch (final InterruptedException e)
            {
                throw new InterruptedIOException();
            }
            finally
            {
                postBlock();
                _lock.unlock();
            }
        }
        
        /**
         * Check the Callback has succeeded or failed and after the return leave in the state to allow reuse.
         */
        @Override
        public void close()
        {
            _lock.lock();
            try
            {
                if (_state == IDLE)
                    throw new IllegalStateException("IDLE");
                if (_state == null)
                    notComplete(this);
            }
            finally
            {
                try 
                {
                    // If the blocker timed itself out, remember the state
                    if (_state instanceof BlockerTimeoutException)
                        // and create a new Blocker as the previous Blocker may still be called back.
                        _blocker=new Blocker();
                    else
                        // else reuse Blocker
                        _state = IDLE;
                    _idle.signalAll();
                    _complete.signalAll();
                } 
                finally 
                {
                    _lock.unlock();
                }
            }
        }

        @Override
        public String toString()
        {
            _lock.lock();
            try
            {
                return String.format("%s@%x{%s}",Blocker.class.getSimpleName(),hashCode(),_state);
            }
            finally
            {
                _lock.unlock();
            }
        }
    }
    
    private static class BlockerTimeoutException extends TimeoutException
    { 
    }
}
