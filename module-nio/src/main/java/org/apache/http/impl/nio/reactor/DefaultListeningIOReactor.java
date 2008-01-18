/*
 * $HeadURL$
 * $Revision$
 * $Date$
 *
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */

package org.apache.http.impl.nio.reactor;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadFactory;

import org.apache.http.nio.reactor.IOReactorException;
import org.apache.http.nio.reactor.IOReactorStatus;
import org.apache.http.nio.reactor.ListenerEndpoint;
import org.apache.http.nio.reactor.ListeningIOReactor;
import org.apache.http.params.HttpParams;

public class DefaultListeningIOReactor extends AbstractMultiworkerIOReactor 
        implements ListeningIOReactor {

    private final Queue<ListenerEndpointImpl> requestQueue;
    private final Set<SocketAddress> pausedEndpoints;
    
    public DefaultListeningIOReactor(
            int workerCount, 
            final ThreadFactory threadFactory,
            final HttpParams params) throws IOReactorException {
        super(workerCount, threadFactory, params);
        this.requestQueue = new ConcurrentLinkedQueue<ListenerEndpointImpl>();
        this.pausedEndpoints = new HashSet<SocketAddress>();
    }

    public DefaultListeningIOReactor(
            int workerCount, 
            final HttpParams params) throws IOReactorException {
        this(workerCount, null, params);
    }
    
    
    @Override
    protected void cancelRequests() throws IOReactorException {
        ListenerEndpointImpl request;
        while ((request = this.requestQueue.poll()) != null) {
            request.cancel();
        }
    }

    @Override
    protected void processEvents(int readyCount) throws IOReactorException {
        processSessionRequests();

        if (readyCount > 0) {
            Set<SelectionKey> selectedKeys = this.selector.selectedKeys();
            for (Iterator<SelectionKey> it = selectedKeys.iterator(); it.hasNext(); ) {
                
                SelectionKey key = it.next();
                processEvent(key);
                
            }
            selectedKeys.clear();
        }
    }

    private void processEvent(final SelectionKey key) 
            throws IOReactorException {
        try {
            
            if (key.isAcceptable()) {
                
                ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
                SocketChannel socketChannel = null;
                try {
                    socketChannel = serverChannel.accept();
                } catch (IOException ex) {
                    if (this.exceptionHandler == null || !this.exceptionHandler.handle(ex)) {
                        throw new IOReactorException("Failure accepting connection", ex);
                    }
                }
                
                if (socketChannel != null) {
                    try {
                        prepareSocket(socketChannel.socket());
                    } catch (IOException ex) {
                        if (this.exceptionHandler == null || !this.exceptionHandler.handle(ex)) {
                            throw new IOReactorException("Failure initalizing socket", ex);
                        }
                    }
                    ChannelEntry entry = new ChannelEntry(socketChannel); 
                    addChannel(entry);
                }
            }
            
        } catch (CancelledKeyException ex) {
            key.attach(null);
        }
    }

    public ListenerEndpoint listen(final SocketAddress address) {
        if (this.status.compareTo(IOReactorStatus.ACTIVE) > 0) {
            throw new IllegalStateException("I/O reactor has been shut down");
        }
        ListenerEndpointImpl request = new ListenerEndpointImpl(address);
        this.requestQueue.add(request);
        this.selector.wakeup();
        return request;
    }

    private void processSessionRequests() throws IOReactorException {
        ListenerEndpointImpl request;
        while ((request = this.requestQueue.poll()) != null) {
            SocketAddress address = request.getAddress();
            ServerSocketChannel serverChannel;
            try {
                serverChannel = ServerSocketChannel.open();
                serverChannel.configureBlocking(false);
            } catch (IOException ex) {
                throw new IOReactorException("Failure opening server socket", ex);
            }
            try {
                serverChannel.socket().bind(address);
            } catch (IOException ex) {
                request.failed(ex);
                if (this.exceptionHandler == null || !this.exceptionHandler.handle(ex)) {
                    throw new IOReactorException("Failure binding socket to address " 
                            + address, ex);
                } else {
                    return;
                }
            }
            try {
                SelectionKey key = serverChannel.register(this.selector, SelectionKey.OP_ACCEPT);
                key.attach(request);
                request.setKey(key);
            } catch (IOException ex) {
                throw new IOReactorException("Failure registering channel " +
                        "with the selector", ex);
            }
            request.completed(serverChannel);
        }
    }
    
    public ListenerEndpoint[] getEndpoints() {
        List<ListenerEndpoint> list = new ArrayList<ListenerEndpoint>();
        if (this.selector.isOpen()) {
            Set<SelectionKey> keys = this.selector.keys();
            synchronized (keys) {
                for (Iterator<SelectionKey> it = keys.iterator(); it.hasNext(); ) {
                    SelectionKey key = it.next();
                    if (key.isValid()) {
                        ListenerEndpoint endpoint = (ListenerEndpoint) key.attachment();
                        if (endpoint != null) {
                            list.add(endpoint);
                        }
                    }
                }
            }
        }
        return list.toArray(new ListenerEndpoint[list.size()]);
    }

    public void pause() throws IOException {
        if (this.selector.isOpen()) {
            Set<SelectionKey> keys = this.selector.keys();
            synchronized (keys) {
                for (Iterator<SelectionKey> it = keys.iterator(); it.hasNext(); ) {
                    SelectionKey key = it.next();
                    if (key.isValid()) {
                        ListenerEndpointImpl endpoint = (ListenerEndpointImpl) key.attachment();
                        if (endpoint != null) {
                            endpoint.close();
                            this.pausedEndpoints.add(endpoint.getAddress());
                        }
                    }
                }
            }
        }
    }

    public void resume() throws IOException {
        for (SocketAddress socketAddress: this.pausedEndpoints) {
            ListenerEndpointImpl request = new ListenerEndpointImpl(socketAddress);
            this.requestQueue.add(request);
        }
        this.pausedEndpoints.clear();
        this.selector.wakeup();
    }
    
}