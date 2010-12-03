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
package org.apache.directory.server.kerberos.kdc.authentication;


import java.io.IOException;
import java.net.InetAddress;
import java.util.Date;
import java.util.List;
import java.util.Set;

import javax.security.auth.kerberos.KerberosKey;

import org.apache.directory.server.i18n.I18n;
import org.apache.directory.server.kerberos.kdc.KdcContext;
import org.apache.directory.server.kerberos.kdc.KdcServer;
import org.apache.directory.server.kerberos.sam.SamException;
import org.apache.directory.server.kerberos.sam.SamSubsystem;
import org.apache.directory.server.kerberos.shared.KerberosConstants;
import org.apache.directory.server.kerberos.shared.crypto.encryption.CipherTextHandler;
import org.apache.directory.server.kerberos.shared.crypto.encryption.KeyUsage;
import org.apache.directory.server.kerberos.shared.crypto.encryption.RandomKeyFactory;
import org.apache.directory.server.kerberos.shared.exceptions.ErrorType;
import org.apache.directory.server.kerberos.shared.exceptions.KerberosException;
import org.apache.directory.server.kerberos.shared.io.decoder.EncryptedDataDecoder;
import org.apache.directory.server.kerberos.shared.messages.AuthenticationReply;
import org.apache.directory.server.kerberos.shared.messages.KdcReply;
import org.apache.directory.server.kerberos.shared.messages.value.EncryptionTypeInfoEntry;
import org.apache.directory.server.kerberos.shared.replay.InMemoryReplayCache;
import org.apache.directory.server.kerberos.shared.replay.ReplayCache;
import org.apache.directory.server.kerberos.shared.store.PrincipalStore;
import org.apache.directory.server.kerberos.shared.store.PrincipalStoreEntry;
import org.apache.directory.shared.kerberos.KerberosTime;
import org.apache.directory.shared.kerberos.KerberosUtils;
import org.apache.directory.shared.kerberos.codec.options.KdcOptions;
import org.apache.directory.shared.kerberos.codec.types.EncryptionType;
import org.apache.directory.shared.kerberos.codec.types.PaDataType;
import org.apache.directory.shared.kerberos.components.EncTicketPart;
import org.apache.directory.shared.kerberos.components.EncryptedData;
import org.apache.directory.shared.kerberos.components.EncryptionKey;
import org.apache.directory.shared.kerberos.components.KdcReq;
import org.apache.directory.shared.kerberos.components.LastReq;
import org.apache.directory.shared.kerberos.components.PaData;
import org.apache.directory.shared.kerberos.components.PaEncTimestamp;
import org.apache.directory.shared.kerberos.components.PaEncTsEnc;
import org.apache.directory.shared.kerberos.components.PrincipalName;
import org.apache.directory.shared.kerberos.components.TransitedEncoding;
import org.apache.directory.shared.kerberos.exceptions.InvalidTicketException;
import org.apache.directory.shared.kerberos.flags.TicketFlag;
import org.apache.directory.shared.kerberos.flags.TicketFlags;
import org.apache.directory.shared.kerberos.messages.Ticket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * @author <a href="mailto:dev@directory.apache.org">Apache Directory Project</a>
 */
public class AuthenticationService
{
    /** The log for this class. */
    private static final Logger LOG = LoggerFactory.getLogger( AuthenticationService.class );

    private static final ReplayCache replayCache = new InMemoryReplayCache();
    private static final CipherTextHandler cipherTextHandler = new CipherTextHandler();

    private static final String SERVICE_NAME = "Authentication Service (AS)";


    public static void execute( AuthenticationContext authContext ) throws Exception
    {
        if ( LOG.isDebugEnabled() )
        {
            monitorRequest( authContext );
        }
        
        authContext.setReplayCache( replayCache );
        authContext.setCipherTextHandler( cipherTextHandler );

        if ( authContext.getRequest().getProtocolVersionNumber() != KerberosConstants.KERBEROS_V5 )
        {
            throw new KerberosException( ErrorType.KDC_ERR_BAD_PVNO );
        }

        selectEncryptionType( authContext );
        getClientEntry( authContext );
        verifyPolicy( authContext );
        verifySam( authContext );
        verifyEncryptedTimestamp( authContext );
        
        if ( authContext.getClientKey() == null )
        {
            verifyEncryptedTimestamp( authContext );
        }

        getServerEntry( authContext );
        generateTicket( authContext );
        buildReply( authContext );

        if ( LOG.isDebugEnabled() )
        {
            monitorContext( authContext );
            monitorReply( ( KdcContext ) authContext );
        }
        
        sealReply( authContext );
    }

    
    private static void selectEncryptionType( AuthenticationContext authContext ) throws KerberosException, InvalidTicketException
    {
        KdcContext kdcContext = ( KdcContext ) authContext;
        KdcServer config = kdcContext.getConfig();

        Set<EncryptionType> requestedTypes = kdcContext.getRequest().getKdcReqBody().getEType();

        EncryptionType bestType = KerberosUtils.getBestEncryptionType( requestedTypes, config.getEncryptionTypes() );

        LOG.debug( "Session will use encryption type {}.", bestType );

        if ( bestType == null )
        {
            throw new KerberosException( ErrorType.KDC_ERR_ETYPE_NOSUPP );
        }

        kdcContext.setEncryptionType( bestType );
    }

    
    private static void getClientEntry( AuthenticationContext authContext ) throws KerberosException, InvalidTicketException
    {
        PrincipalName principal = authContext.getRequest().getKdcReqBody().getCName();
        PrincipalStore store = authContext.getStore();

        PrincipalStoreEntry storeEntry = getEntry( principal, store, ErrorType.KDC_ERR_C_PRINCIPAL_UNKNOWN ); 
        authContext.setClientEntry( storeEntry );
    }
    
    
    private static void verifyPolicy( AuthenticationContext authContext ) throws KerberosException, InvalidTicketException
    {
        PrincipalStoreEntry entry = authContext.getClientEntry();

        if ( entry.isDisabled() )
        {
            throw new KerberosException( ErrorType.KDC_ERR_CLIENT_REVOKED );
        }

        if ( entry.isLockedOut() )
        {
            throw new KerberosException( ErrorType.KDC_ERR_CLIENT_REVOKED );
        }

        if ( entry.getExpiration().getTime() < new Date().getTime() )
        {
            throw new KerberosException( ErrorType.KDC_ERR_CLIENT_REVOKED );
        }
    }
    
    
    private static void verifySam( AuthenticationContext authContext ) throws KerberosException, InvalidTicketException
    {
        LOG.debug( "Verifying using SAM subsystem." );
        KdcReq request = authContext.getRequest();
        KdcServer config = authContext.getConfig();

        PrincipalStoreEntry clientEntry = authContext.getClientEntry();
        String clientName = clientEntry.getPrincipal().getNameString();

        EncryptionKey clientKey = null;

        if ( clientEntry.getSamType() != null )
        {
            if ( LOG.isDebugEnabled() )
            {
                LOG.debug( "Entry for client principal {} has a valid SAM type.  Invoking SAM subsystem for pre-authentication.", clientName );
            }

            List<PaData> preAuthData = request.getPaData();

            if ( preAuthData == null || preAuthData.size() == 0 )
            {
                throw new KerberosException( ErrorType.KDC_ERR_PREAUTH_REQUIRED, preparePreAuthenticationError( config
                    .getEncryptionTypes() ) );
            }

            try
            {
                for ( PaData paData : preAuthData )
                {
                    if ( paData.getPaDataType().equals( PaDataType.PA_ENC_TIMESTAMP ) )
                    {
                        KerberosKey samKey = SamSubsystem.getInstance().verify( clientEntry,
                            paData.getPaDataValue() );
                        clientKey = new EncryptionKey( EncryptionType.getTypeByValue( samKey.getKeyType() ), samKey
                            .getEncoded() );
                    }
                }
            }
            catch ( SamException se )
            {
                throw new KerberosException( ErrorType.KRB_ERR_GENERIC, se );
            }

            authContext.setClientKey( clientKey );
            authContext.setPreAuthenticated( true );

            if ( LOG.isDebugEnabled() )
            {
                LOG.debug( "Pre-authentication using SAM subsystem successful for {}.", clientName );
            }
        }
    }
    
    
    private static void verifyEncryptedTimestamp( AuthenticationContext authContext ) throws KerberosException, InvalidTicketException
    {
        LOG.debug( "Verifying using encrypted timestamp." );
        
        KdcServer config = authContext.getConfig();
        KdcReq request = authContext.getRequest();
        CipherTextHandler cipherTextHandler = authContext.getCipherTextHandler();
        PrincipalStoreEntry clientEntry = authContext.getClientEntry();
        String clientName = clientEntry.getPrincipal().getNameString();

        EncryptionKey clientKey = null;

        if ( clientEntry.getSamType() == null )
        {
            if ( LOG.isDebugEnabled() )
            {
                LOG.debug(
                    "Entry for client principal {} has no SAM type.  Proceeding with standard pre-authentication.",
                    clientName );
            }

            EncryptionType encryptionType = authContext.getEncryptionType();
            clientKey = clientEntry.getKeyMap().get( encryptionType );

            if ( clientKey == null )
            {
                throw new KerberosException( ErrorType.KDC_ERR_NULL_KEY );
            }

            if ( config.isPaEncTimestampRequired() )
            {
                List<PaData> preAuthData = request.getPaData();

                if ( preAuthData == null )
                {
                    throw new KerberosException( ErrorType.KDC_ERR_PREAUTH_REQUIRED,
                        preparePreAuthenticationError( config.getEncryptionTypes() ) );
                }

                PaEncTsEnc timestamp = null;

                for ( PaData paData : preAuthData )
                {
                    if ( paData.getPaDataType().equals( PaDataType.PA_ENC_TIMESTAMP ) )
                    {
                        EncryptedData dataValue;

                        try
                        {
                            dataValue = EncryptedDataDecoder.decode( paData.getPaDataValue() );
                        }
                        catch ( IOException ioe )
                        {
                            throw new KerberosException( ErrorType.KRB_AP_ERR_BAD_INTEGRITY, ioe );
                        }
                        catch ( ClassCastException cce )
                        {
                            throw new KerberosException( ErrorType.KRB_AP_ERR_BAD_INTEGRITY, cce );
                        }

                        timestamp = ( PaEncTsEnc ) cipherTextHandler.unseal( PaEncTimestamp.class,
                            clientKey, dataValue, KeyUsage.NUMBER1 );
                    }
                }

                if ( ( preAuthData.size() > 0 ) && ( timestamp == null ) )
                {
                    throw new KerberosException( ErrorType.KDC_ERR_PADATA_TYPE_NOSUPP );
                }

                if ( timestamp == null )
                {
                    throw new KerberosException( ErrorType.KDC_ERR_PREAUTH_REQUIRED,
                        preparePreAuthenticationError( config.getEncryptionTypes() ) );
                }

                if ( !timestamp.getPaTimestamp().isInClockSkew( config.getAllowableClockSkew() ) )
                {
                    throw new KerberosException( ErrorType.KDC_ERR_PREAUTH_FAILED );
                }

                /*
                 * if(decrypted_enc_timestamp and usec is replay)
                 *         error_out(KDC_ERR_PREAUTH_FAILED);
                 * endif
                 * 
                 * add decrypted_enc_timestamp and usec to replay cache;
                 */
            }
        }

        authContext.setClientKey( clientKey );
        authContext.setPreAuthenticated( true );

        if ( LOG.isDebugEnabled() )
        {
            LOG.debug( "Pre-authentication by encrypted timestamp successful for {}.", clientName );
        }
    }
    
    
    private static void getServerEntry( AuthenticationContext authContext ) throws KerberosException, InvalidTicketException
    {
        PrincipalName principal = authContext.getRequest().getKdcReqBody().getSName();
        PrincipalStore store = authContext.getStore();
    
        authContext.setServerEntry( getEntry( principal, store, ErrorType.KDC_ERR_S_PRINCIPAL_UNKNOWN ) );
    }    
    
    
    private static void generateTicket( AuthenticationContext authContext ) throws KerberosException, InvalidTicketException
    {
        KdcReq request = authContext.getRequest();
        CipherTextHandler cipherTextHandler = authContext.getCipherTextHandler();
        PrincipalName serverPrincipal = request.getKdcReqBody().getSName();

        EncryptionType encryptionType = authContext.getEncryptionType();
        EncryptionKey serverKey = authContext.getServerEntry().getKeyMap().get( encryptionType );

        PrincipalName ticketPrincipal = request.getKdcReqBody().getSName();
        
        EncTicketPart encTicketPart = new EncTicketPart();
        KdcServer config = authContext.getConfig();

        // The INITIAL flag indicates that a ticket was issued using the AS protocol.
        TicketFlags ticketFlags = new TicketFlags();
        encTicketPart.setFlags( ticketFlags );
        ticketFlags.setFlag( TicketFlag.INITIAL );

        // The PRE-AUTHENT flag indicates that the client used pre-authentication.
        if ( authContext.isPreAuthenticated() )
        {
            ticketFlags.setFlag( TicketFlag.PRE_AUTHENT );
        }

        if ( request.getKdcReqBody().getKdcOptions().get( KdcOptions.FORWARDABLE ) )
        {
            if ( !config.isForwardableAllowed() )
            {
                throw new KerberosException( ErrorType.KDC_ERR_POLICY );
            }

            ticketFlags.setFlag( TicketFlag.FORWARDABLE );
        }

        if ( request.getKdcReqBody().getKdcOptions().get( KdcOptions.PROXIABLE ) )
        {
            if ( !config.isProxiableAllowed() )
            {
                throw new KerberosException( ErrorType.KDC_ERR_POLICY );
            }

            ticketFlags.setFlag( TicketFlag.PROXIABLE );
        }

        if ( request.getKdcReqBody().getKdcOptions().get( KdcOptions.ALLOW_POSTDATE ) )
        {
            if ( !config.isPostdatedAllowed() )
            {
                throw new KerberosException( ErrorType.KDC_ERR_POLICY );
            }

            ticketFlags.setFlag( TicketFlag.MAY_POSTDATE );
        }

        if ( request.getKdcReqBody().getKdcOptions().get( KdcOptions.RENEW ) 
            || request.getKdcReqBody().getKdcOptions().get( KdcOptions.VALIDATE )
            || request.getKdcReqBody().getKdcOptions().get( KdcOptions.PROXY ) 
            || request.getKdcReqBody().getKdcOptions().get( KdcOptions.FORWARDED )
            || request.getKdcReqBody().getKdcOptions().get( KdcOptions.ENC_TKT_IN_SKEY ) )
        {
            throw new KerberosException( ErrorType.KDC_ERR_BADOPTION );
        }

        EncryptionKey sessionKey = RandomKeyFactory.getRandomKey( authContext.getEncryptionType() );
        encTicketPart.setKey( sessionKey );

        encTicketPart.setcName( request.getKdcReqBody().getCName() );
        encTicketPart.setTransited( new TransitedEncoding() );

        KerberosTime now = new KerberosTime();

        encTicketPart.setAuthTime( now );

        KerberosTime startTime = request.getKdcReqBody().getFrom();

        /*
         * "If the requested starttime is absent, indicates a time in the past,
         * or is within the window of acceptable clock skew for the KDC and the
         * POSTDATE option has not been specified, then the starttime of the
         * ticket is set to the authentication server's current time."
         */
        if ( startTime == null || startTime.lessThan( now ) || startTime.isInClockSkew( config.getAllowableClockSkew() )
            && !request.getKdcReqBody().getKdcOptions().get( KdcOptions.POSTDATED ) )
        {
            startTime = now;
        }

        /*
         * "If it indicates a time in the future beyond the acceptable clock skew,
         * but the POSTDATED option has not been specified, then the error
         * KDC_ERR_CANNOT_POSTDATE is returned."
         */
        if ( startTime != null && startTime.greaterThan( now )
            && !startTime.isInClockSkew( config.getAllowableClockSkew() ) 
            && !request.getKdcReqBody().getKdcOptions().get( KdcOptions.POSTDATED ) )
        {
            throw new KerberosException( ErrorType.KDC_ERR_CANNOT_POSTDATE );
        }

        /*
         * "Otherwise the requested starttime is checked against the policy of the
         * local realm and if the ticket's starttime is acceptable, it is set as
         * requested, and the INVALID flag is set in the new ticket."
         */
        if ( request.getKdcReqBody().getKdcOptions().get( KdcOptions.POSTDATED ) )
        {
            if ( !config.isPostdatedAllowed() )
            {
                throw new KerberosException( ErrorType.KDC_ERR_POLICY );
            }

            ticketFlags.setFlag( TicketFlag.POSTDATED );
            ticketFlags.setFlag( TicketFlag.INVALID );
            encTicketPart.setStartTime( startTime );
        }

        long till = 0;
        
        if ( request.getKdcReqBody().getTill().getTime() == 0 )
        {
            till = Long.MAX_VALUE;
        }
        else
        {
            till = request.getKdcReqBody().getTill().getTime();
        }

        /*
         * The end time is the minimum of (a) the requested till time or (b)
         * the start time plus maximum lifetime as configured in policy.
         */
        long endTime = Math.min( till, startTime.getTime() + config.getMaximumTicketLifetime() );
        KerberosTime kerberosEndTime = new KerberosTime( endTime );
        encTicketPart.setEndTime( kerberosEndTime );

        /*
         * "If the requested expiration time minus the starttime (as determined
         * above) is less than a site-determined minimum lifetime, an error
         * message with code KDC_ERR_NEVER_VALID is returned."
         */
        if ( kerberosEndTime.lessThan( startTime ) )
        {
            throw new KerberosException( ErrorType.KDC_ERR_NEVER_VALID );
        }

        long ticketLifeTime = Math.abs( startTime.getTime() - kerberosEndTime.getTime() );
        
        if ( ticketLifeTime < config.getAllowableClockSkew() )
        {
            throw new KerberosException( ErrorType.KDC_ERR_NEVER_VALID );
        }

        /*
         * "If the requested expiration time for the ticket exceeds what was determined
         * as above, and if the 'RENEWABLE-OK' option was requested, then the 'RENEWABLE'
         * flag is set in the new ticket, and the renew-till value is set as if the
         * 'RENEWABLE' option were requested."
         */
        KerberosTime tempRtime = request.getKdcReqBody().getRTime();

        if ( request.getKdcReqBody().getKdcOptions().get( KdcOptions.RENEWABLE_OK ) 
            && request.getKdcReqBody().getTill().greaterThan( kerberosEndTime ) )
        {
            if ( !config.isRenewableAllowed() )
            {
                throw new KerberosException( ErrorType.KDC_ERR_POLICY );
            }

            request.getKdcReqBody().getKdcOptions().set( KdcOptions.RENEWABLE );
            tempRtime = request.getKdcReqBody().getTill();
        }

        if ( request.getKdcReqBody().getKdcOptions().get( KdcOptions.RENEWABLE ) )
        {
            if ( !config.isRenewableAllowed() )
            {
                throw new KerberosException( ErrorType.KDC_ERR_POLICY );
            }

            ticketFlags.setFlag( TicketFlag.RENEWABLE );

            if ( tempRtime == null || tempRtime.isZero() )
            {
                tempRtime = KerberosTime.INFINITY;
            }

            /*
             * The renew-till time is the minimum of (a) the requested renew-till
             * time or (b) the start time plus maximum renewable lifetime as
             * configured in policy.
             */
            long renewTill = Math.min( tempRtime.getTime(), startTime.getTime() + config.getMaximumRenewableLifetime() );
            encTicketPart.setRenewTill( new KerberosTime( renewTill ) );
        }

        if ( request.getKdcReqBody().getAddresses() != null && request.getKdcReqBody().getAddresses().getAddresses() != null
            && request.getKdcReqBody().getAddresses().getAddresses().length > 0 )
        {
            encTicketPart.setClientAddresses( request.getKdcReqBody().getAddresses() );
        }
        else
        {
            if ( !config.isEmptyAddressesAllowed() )
            {
                throw new KerberosException( ErrorType.KDC_ERR_POLICY );
            }
        }

        EncryptedData encryptedData = cipherTextHandler.seal( serverKey, encTicketPart, KeyUsage.NUMBER2 );

        Ticket newTicket = new Ticket( ticketPrincipal, encryptedData );
        newTicket.setEncTicketPart( encTicketPart );

        if ( LOG.isDebugEnabled() )
        {
            LOG.debug( "Ticket will be issued for access to {}.", serverPrincipal.toString() );
        }

        authContext.setTicket( newTicket );
    }
    
    
    private static void buildReply( AuthenticationContext authContext ) throws KerberosException, InvalidTicketException
    {
        KdcReq request = authContext.getRequest();
        Ticket ticket = authContext.getTicket();

        AuthenticationReply reply = new AuthenticationReply();

        reply.setClientPrincipal( request.getKdcReqBody().getCName() );
        reply.setTicket( ticket );
        reply.setKey( ticket.getEncTicketPart().getSessionKey() );

        // TODO - fetch lastReq for this client; requires store
        reply.setLastReq( new LastReq() );
        // TODO - resp.key-expiration := client.expiration; requires store

        reply.setNonce( request.getKdcReqBody().getNonce() );

        reply.setFlags( ticket.getEncTicketPart().getFlags() );
        reply.setAuthTime( ticket.getEncTicketPart().getAuthTime() );
        reply.setStartTime( ticket.getEncTicketPart().getStartTime() );
        reply.setEndTime( ticket.getEncTicketPart().getEndTime() );

        if ( ticket.getEncTicketPart().getFlags().isRenewable() )
        {
            reply.setRenewTill( ticket.getEncTicketPart().getRenewTill() );
        }

        reply.setServerPrincipal( ticket.getServerPrincipal() );
        reply.setClientAddresses( ticket.getEncTicketPart().getClientAddresses() );

        authContext.setReply( reply );
    }
    
    
    private static void sealReply( AuthenticationContext authContext ) throws KerberosException, InvalidTicketException
    {
        AuthenticationReply reply = ( AuthenticationReply ) authContext.getReply();
        EncryptionKey clientKey = authContext.getClientKey();
        CipherTextHandler cipherTextHandler = authContext.getCipherTextHandler();

        EncryptedData encryptedData = cipherTextHandler.seal( clientKey, reply, KeyUsage.NUMBER3 );
        reply.setEncPart( encryptedData );
    }
    
    
    private static void monitorRequest( KdcContext kdcContext )
    {
        KdcReq request = kdcContext.getRequest();

        if ( LOG.isDebugEnabled() )
        {
            try
            {
                String clientAddress = kdcContext.getClientAddress().getHostAddress();

                StringBuffer sb = new StringBuffer();

                sb.append( "Received " + SERVICE_NAME + " request:" );
                sb.append( "\n\t" + "messageType:           " + request.getMessageType() );
                sb.append( "\n\t" + "protocolVersionNumber: " + request.getProtocolVersionNumber() );
                sb.append( "\n\t" + "clientAddress:         " + clientAddress );
                sb.append( "\n\t" + "nonce:                 " + request.getKdcReqBody().getNonce() );
                sb.append( "\n\t" + "kdcOptions:            " + request.getKdcReqBody().getKdcOptions() );
                sb.append( "\n\t" + "clientPrincipal:       " + request.getClientPrincipal() );
                sb.append( "\n\t" + "serverPrincipal:       " + request.getServerPrincipal() );
                sb.append( "\n\t" + "encryptionType:        " + KerberosUtils.getEncryptionTypesString( request.getKdcReqBody().getEType() ) );
                sb.append( "\n\t" + "realm:                 " + request.getKdcReqBody().getRealm() );
                sb.append( "\n\t" + "from time:             " + request.getKdcReqBody().getFrom() );
                sb.append( "\n\t" + "till time:             " + request.getKdcReqBody().getTill() );
                sb.append( "\n\t" + "renew-till time:       " + request.getKdcReqBody().getRTime() );
                sb.append( "\n\t" + "hostAddresses:         " + request.getKdcReqBody().getAddresses() );

                LOG.debug( sb.toString() );
            }
            catch ( Exception e )
            {
                // This is a monitor.  No exceptions should bubble up.
                LOG.error( I18n.err( I18n.ERR_153 ), e );
            }
        }
    }
    
    private static void monitorContext( AuthenticationContext authContext )
    {
        try
        {
            long clockSkew = authContext.getConfig().getAllowableClockSkew();
            InetAddress clientAddress = authContext.getClientAddress();

            StringBuilder sb = new StringBuilder();

            sb.append( "Monitoring " + SERVICE_NAME + " context:" );

            sb.append( "\n\t" + "clockSkew              " + clockSkew );
            sb.append( "\n\t" + "clientAddress          " + clientAddress );

            PrincipalName clientPrincipal = authContext.getClientEntry().getPrincipal();
            PrincipalStoreEntry clientEntry = authContext.getClientEntry();

            sb.append( "\n\t" + "principal              " + clientPrincipal );
            sb.append( "\n\t" + "cn                     " + clientEntry.getCommonName() );
            sb.append( "\n\t" + "realm                  " + clientEntry.getRealmName() );
            sb.append( "\n\t" + "principal              " + clientEntry.getPrincipal() );
            sb.append( "\n\t" + "SAM type               " + clientEntry.getSamType() );

            PrincipalName serverPrincipal = authContext.getRequest().getServerPrincipal();
            PrincipalStoreEntry serverEntry = authContext.getServerEntry();

            sb.append( "\n\t" + "principal              " + serverPrincipal );
            sb.append( "\n\t" + "cn                     " + serverEntry.getCommonName() );
            sb.append( "\n\t" + "realm                  " + serverEntry.getRealmName() );
            sb.append( "\n\t" + "principal              " + serverEntry.getPrincipal() );
            sb.append( "\n\t" + "SAM type               " + serverEntry.getSamType() );

            EncryptionType encryptionType = authContext.getEncryptionType();
            int clientKeyVersion = clientEntry.getKeyMap().get( encryptionType ).getKeyVersion();
            int serverKeyVersion = serverEntry.getKeyMap().get( encryptionType ).getKeyVersion();
            sb.append( "\n\t" + "Request key type       " + encryptionType );
            sb.append( "\n\t" + "Client key version     " + clientKeyVersion );
            sb.append( "\n\t" + "Server key version     " + serverKeyVersion );

            LOG.debug( sb.toString() );
        }
        catch ( Exception e )
        {
            // This is a monitor.  No exceptions should bubble up.
            LOG.error( I18n.err( I18n.ERR_154 ), e );
        }
    }
    
    
    private static void monitorReply( KdcContext kdcContext )
    {
        Object reply = kdcContext.getReply();

        if ( LOG.isDebugEnabled() && reply instanceof KdcReply )
        {
            KdcReply success = ( KdcReply ) reply;

            try
            {
                StringBuffer sb = new StringBuffer();

                sb.append( "Responding with " + SERVICE_NAME + " reply:" );
                sb.append( "\n\t" + "messageType:           " + success.getMessageType() );
                sb.append( "\n\t" + "protocolVersionNumber: " + success.getProtocolVersionNumber() );
                sb.append( "\n\t" + "nonce:                 " + success.getNonce() );
                sb.append( "\n\t" + "clientPrincipal:       " + success.getClientPrincipal() );
                sb.append( "\n\t" + "client realm:          " + success.getClientRealm() );
                sb.append( "\n\t" + "serverPrincipal:       " + success.getServerPrincipal() );
                sb.append( "\n\t" + "server realm:          " + success.getServerRealm() );
                sb.append( "\n\t" + "auth time:             " + success.getAuthTime() );
                sb.append( "\n\t" + "start time:            " + success.getStartTime() );
                sb.append( "\n\t" + "end time:              " + success.getEndTime() );
                sb.append( "\n\t" + "renew-till time:       " + success.getRenewTill() );
                sb.append( "\n\t" + "hostAddresses:         " + success.getClientAddresses() );

                LOG.debug( sb.toString() );
            }
            catch ( Exception e )
            {
                // This is a monitor.  No exceptions should bubble up.
                LOG.error( I18n.err( I18n.ERR_155 ), e );
            }
        }
    }
    
    
    /**
     * Get a PrincipalStoreEntry given a principal.  The ErrorType is used to indicate
     * whether any resulting error pertains to a server or client.
     */
    private static PrincipalStoreEntry getEntry( PrincipalName principal, PrincipalStore store, ErrorType errorType )
        throws KerberosException
    {
        PrincipalStoreEntry entry = null;

        try
        {
            entry = store.getPrincipal( principal );
        }
        catch ( Exception e )
        {
            throw new KerberosException( errorType, e );
        }

        if ( entry == null )
        {
            throw new KerberosException( errorType );
        }

        if ( entry.getKeyMap() == null || entry.getKeyMap().isEmpty() )
        {
            throw new KerberosException( ErrorType.KDC_ERR_NULL_KEY );
        }

        return entry;
    }
    
    
    /**
     * Prepares a pre-authentication error message containing required
     * encryption types.
     *
     * @param encryptionTypes
     * @return The error message as bytes.
     */
    private static byte[] preparePreAuthenticationError( Set<EncryptionType> encryptionTypes )
    {
        PaData[] paDataSequence = new PaData[2];

        PaData paData = new PaData();
        paData.setPaDataType( PaDataType.PA_ENC_TIMESTAMP );
        paData.setPaDataValue( new byte[0] );

        paDataSequence[0] = paData;

        EncryptionTypeInfoEntry[] entries = new EncryptionTypeInfoEntry[ encryptionTypes.size() ];
        int i = 0;
        
        for ( EncryptionType encryptionType:encryptionTypes )
        {
            entries[i++] = new EncryptionTypeInfoEntry( encryptionType, null );
        }

        byte[] encTypeInfo = null;

        try
        {
            encTypeInfo = EncryptionTypeInfoEncoder.encode( entries );
        }
        catch ( IOException ioe )
        {
            return null;
        }

        PaData encType = new PaData();
        encType.setPaDataType( PaDataType.PA_ENCTYPE_INFO );
        encType.setPaDataValue( encTypeInfo );

        paDataSequence[1] = encType;

        try
        {
            return PreAuthenticationDataEncoder.encode( paDataSequence );
        }
        catch ( IOException ioe )
        {
            return null;
        }
    }
}
