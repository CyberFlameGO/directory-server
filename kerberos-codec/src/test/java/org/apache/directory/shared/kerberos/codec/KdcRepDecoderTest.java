/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *  
 *    http://www.apache.org/licenses/LICENSE-2.0
 *  
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License. 
 *  
 */
package org.apache.directory.shared.kerberos.codec;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.ByteBuffer;

import org.apache.directory.api.asn1.DecoderException;
import org.apache.directory.api.asn1.EncoderException;
import org.apache.directory.api.asn1.ber.Asn1Container;
import org.apache.directory.api.asn1.ber.Asn1Decoder;
import org.apache.directory.shared.kerberos.codec.kdcRep.KdcRepContainer;
import org.apache.directory.shared.kerberos.components.KdcRep;
import org.apache.directory.shared.kerberos.messages.AsRep;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


/**
 * Test the decoder for a KDC-REP
 * @author <a href="mailto:dev@directory.apache.org">Apache Directory Project</a>
 */
public class KdcRepDecoderTest
{
    /**
     * Test the decoding of a KDC-REP message
     */
    @Test
    public void testDecodeFullKdcRep() throws Exception
    {

        ByteBuffer stream = ByteBuffer.allocate( 0xA9 );

        stream.put( new byte[]
            {
                0x30, ( byte ) 0x81, ( byte ) 0xA6,
                ( byte ) 0xA0, 0x03, // PVNO
                0x02,
                0x01,
                0x05,
                ( byte ) 0xA1,
                0x03, // msg-type
                0x02,
                0x01,
                0x0B,
                ( byte ) 0xA2,
                0x20, // PA-DATA
                0x30,
                0x1E,
                0x30,
                0x0D,
                ( byte ) 0xA1,
                0x03,
                0x02,
                0x01,
                01,
                ( byte ) 0xA2,
                0x06,
                0x04,
                0x04,
                'a',
                'b',
                'c',
                'd',
                0x30,
                0x0D,
                ( byte ) 0xA1,
                0x03,
                0x02,
                0x01,
                01,
                ( byte ) 0xA2,
                0x06,
                0x04,
                0x04,
                'e',
                'f',
                'g',
                'h',
                ( byte ) 0xA3,
                0x0D, // crealm
                0x1B,
                0x0B,
                'E',
                'X',
                'A',
                'M',
                'P',
                'L',
                'E',
                '.',
                'C',
                'O',
                'M',
                ( byte ) 0xA4,
                0x14, // cname
                0x30,
                0x12,
                ( byte ) 0xA0,
                0x03, // name-type
                0x02,
                0x01,
                0x01,
                ( byte ) 0xA1,
                0x0B, // name-string
                0x30,
                0x09,
                0x1B,
                0x07,
                'h',
                'n',
                'e',
                'l',
                's',
                'o',
                'n',
                ( byte ) 0xA5,
                0x40, // Ticket
                0x61,
                0x3E,
                0x30,
                0x3C,
                ( byte ) 0xA0,
                0x03,
                0x02,
                0x01,
                0x05,
                ( byte ) 0xA1,
                0x0D,
                0x1B,
                0x0B,
                'E',
                'X',
                'A',
                'M',
                'P',
                'L',
                'E',
                '.',
                'C',
                'O',
                'M',
                ( byte ) 0xA2,
                0x13,
                0x30,
                0x11,
                ( byte ) 0xA0,
                0x03,
                0x02,
                0x01,
                0x01,
                ( byte ) 0xA1,
                0x0A,
                0x30,
                0x08,
                0x1B,
                0x06,
                'c',
                'l',
                'i',
                'e',
                'n',
                't',
                ( byte ) 0xA3,
                0x11,
                0x30,
                0x0F,
                ( byte ) 0xA0,
                0x03,
                0x02,
                0x01,
                0x11,
                ( byte ) 0xA2,
                0x08,
                0x04,
                0x06,
                'a',
                'b',
                'c',
                'd',
                'e',
                'f',
                ( byte ) 0xA6,
                0x11, // enc-part
                0x30,
                0x0F,
                ( byte ) 0xA0,
                0x03,
                0x02,
                0x01,
                0x11,
                ( byte ) 0xA2,
                0x08,
                0x04,
                0x06,
                'a',
                'b',
                'c',
                'd',
                'e',
                'f',
        } );

        stream.flip();

        // Allocate a KdcRep Container
        KdcRepContainer kdcRepContainer = new KdcRepContainer( stream );
        kdcRepContainer.setKdcRep( new AsRep() );

        // Decode the KdcRep PDU
        try
        {
            Asn1Decoder.decode( stream, kdcRepContainer );
        }
        catch ( DecoderException de )
        {
            fail( de.getMessage() );
        }

        KdcRep kdcRep = kdcRepContainer.getKdcRep();

        // Check the encoding
        int length = kdcRep.computeLength();

        // Check the length, should be 3 bytes longer as the kdcRep is a AS-REP
        assertEquals( 0xAC, length );

        // Check the encoding
        ByteBuffer encodedPdu = ByteBuffer.allocate( length );

        try
        {
            encodedPdu = kdcRep.encode( encodedPdu );

            // Check the length
            assertEquals( 0xAC, encodedPdu.limit() );
        }
        catch ( EncoderException ee )
        {
            fail();
        }
    }


    /**
     * Test the decoding of a KDC-REP with nothing in it
     */
    @Test
    public void testKdcRepEmpty() throws DecoderException
    {

        ByteBuffer stream = ByteBuffer.allocate( 0x02 );

        stream.put( new byte[]
            { 0x30, 0x00 } );

        stream.flip();

        // Allocate a KDC-REP Container
        Asn1Container kdcRepContainer = new KdcRepContainer( stream );

        // Decode the KDC-REP PDU
        Assertions.assertThrows( DecoderException.class, () -> {
            Asn1Decoder.decode(stream, kdcRepContainer);
        } );
    }


    /**
     * Test the decoding of a KDC-REP with empty Pvno tag
     */
    @Test
    public void testKdcRepEmptyPvnoTag() throws DecoderException
    {

        ByteBuffer stream = ByteBuffer.allocate( 0x04 );

        stream.put( new byte[]
            {
                0x30, 0x02,
                ( byte ) 0xA0, 0x00
        } );

        stream.flip();

        // Allocate a KDC-REP Container
        Asn1Container kdcRepContainer = new KdcRepContainer( stream );

        // Decode the KDC-REP PDU
        Assertions.assertThrows( DecoderException.class, () -> {
            Asn1Decoder.decode(stream, kdcRepContainer);
        } );
    }


    /**
     * Test the decoding of a KDC-REP with empty Pvno value
     */
    @Test
    public void testKdcRepEmptyPvnoValue() throws DecoderException
    {

        ByteBuffer stream = ByteBuffer.allocate( 0x06 );

        stream.put( new byte[]
            {
                0x30, 0x04,
                ( byte ) 0xA0, 0x02,
                0x02, 0x00
        } );

        stream.flip();

        // Allocate a KDC-REP Container
        Asn1Container kdcRepContainer = new KdcRepContainer( stream );

        // Decode the KDC-REP PDU
        Assertions.assertThrows( DecoderException.class, () -> {
            Asn1Decoder.decode(stream, kdcRepContainer);
        } );
    }
}
