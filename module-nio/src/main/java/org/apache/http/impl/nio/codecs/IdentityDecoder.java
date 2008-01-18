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

package org.apache.http.impl.nio.codecs;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;

import org.apache.http.impl.io.HttpTransportMetricsImpl;
import org.apache.http.nio.FileContentDecoder;
import org.apache.http.nio.reactor.SessionInputBuffer;

/**
 * Identity decoder implementation.
 *
 * @author <a href="mailto:oleg at ural.ru">Oleg Kalnichevski</a>
 * @author Andrea Selva
 *
 * @version $Revision$
 * 
 * @since 4.0
 */
public class IdentityDecoder extends AbstractContentDecoder 
        implements FileContentDecoder {
    
    public IdentityDecoder(
            final ReadableByteChannel channel, 
            final SessionInputBuffer buffer,
            final HttpTransportMetricsImpl metrics) {
        super(channel, buffer, metrics);
    }

    public int read(final ByteBuffer dst) throws IOException {
        if (dst == null) {
            throw new IllegalArgumentException("Byte buffer may not be null");
        }
        if (this.completed) {
            return -1;
        }
        
        int bytesRead;
        if (this.buffer.hasData()) {
            bytesRead = this.buffer.read(dst);
        } else {
            bytesRead = this.channel.read(dst);
            if (bytesRead > 0) {
                this.metrics.incrementBytesTransferred(bytesRead);
            }
        }
        if (bytesRead == -1) {
            this.completed = true;
        }
        return bytesRead;
    }
    
    public long transfer(
            final FileChannel dst, 
            long position, 
            long count) throws IOException {
        
        if (dst == null) {
            return 0;
        }
        if (this.completed) {
            return 0;
        }
        
        long bytesRead;
        if (this.buffer.hasData()) {
            bytesRead = this.buffer.read(dst);
        } else {
            if (this.channel.isOpen()) {
                bytesRead = dst.transferFrom(this.channel, position, count);
            } else {
                bytesRead = -1;
            }
            if (bytesRead > 0) {
                this.metrics.incrementBytesTransferred(bytesRead);
            }
        }
        if (bytesRead == -1) {
            this.completed = true;
        }
        return bytesRead;
    }

    @Override
	public String toString() {
        StringBuffer buffer = new StringBuffer();
        buffer.append("[identity; completed: ");
        buffer.append(this.completed);
        buffer.append("]");
        return buffer.toString();
    }
    
}