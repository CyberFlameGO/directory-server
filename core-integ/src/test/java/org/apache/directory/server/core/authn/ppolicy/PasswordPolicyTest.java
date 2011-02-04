/*
 *   Licensed to the Apache Software Foundation (ASF) under one
 *   or more contributor license agreements.  See the NOTICE file
 *   distributed with this work for additional information
 *   regarding copyright ownership.  The ASF licenses this file
 *   to you under the Apache License, Version 2.0 (the
 *   "License"); you may not use this file except in compliance
 *   with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing,
 *   software distributed under the License is distributed on an
 *   "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *   KIND, either express or implied.  See the License for the
 *   specific language governing permissions and limitations
 *   under the License.
 *
 */

package org.apache.directory.server.core.authn.ppolicy;


import static org.apache.directory.server.core.integ.IntegrationUtils.getAdminNetworkConnection;
import static org.apache.directory.server.core.integ.IntegrationUtils.getNetworkConnectionAs;
import static org.apache.directory.shared.ldap.extras.controls.PasswordPolicyErrorEnum.INSUFFICIENT_PASSWORD_QUALITY;
import static org.apache.directory.shared.ldap.extras.controls.PasswordPolicyErrorEnum.PASSWORD_TOO_SHORT;
import static org.apache.directory.shared.ldap.extras.controls.PasswordPolicyErrorEnum.PASSWORD_TOO_YOUNG;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.apache.directory.ldap.client.api.LdapConnection;
import org.apache.directory.server.annotations.CreateLdapServer;
import org.apache.directory.server.annotations.CreateTransport;
import org.apache.directory.server.core.PasswordPolicyConfiguration;
import org.apache.directory.server.core.PpolicyConfigContainer;
import org.apache.directory.server.core.annotations.CreateDS;
import org.apache.directory.server.core.authn.AuthenticationInterceptor;
import org.apache.directory.server.core.authn.PasswordUtil;
import org.apache.directory.server.core.integ.AbstractLdapTestUnit;
import org.apache.directory.server.core.integ.FrameworkRunner;
import org.apache.directory.server.core.integ.IntegrationUtils;
import org.apache.directory.shared.ldap.codec.DefaultLdapCodecService;
import org.apache.directory.shared.ldap.codec.ICodecControl;
import org.apache.directory.shared.ldap.codec.ILdapCodecService;
import org.apache.directory.shared.ldap.extras.controls.PasswordPolicy;
import org.apache.directory.shared.ldap.extras.controls.PasswordPolicyImpl;
import org.apache.directory.shared.ldap.extras.controls.ppolicy_impl.PasswordPolicyDecorator;
import org.apache.directory.shared.ldap.model.constants.LdapSecurityConstants;
import org.apache.directory.shared.ldap.model.constants.SchemaConstants;
import org.apache.directory.shared.ldap.model.entry.DefaultEntry;
import org.apache.directory.shared.ldap.model.entry.EntryAttribute;
import org.apache.directory.shared.ldap.model.entry.Entry;
import org.apache.directory.shared.ldap.model.exception.LdapException;
import org.apache.directory.shared.ldap.model.ldif.LdifUtils;
import org.apache.directory.shared.ldap.model.message.*;
import org.apache.directory.shared.ldap.model.name.Dn;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;


/**
 * Test cases for testing PasswordPolicy implementation.
 *
 * @author <a href="mailto:dev@directory.apache.org">Apache Directory Project</a>
 */
@RunWith(FrameworkRunner.class)
@CreateLdapServer(transports =
    { @CreateTransport(protocol = "LDAP"), @CreateTransport(protocol = "LDAPS") })
// disable changelog, for more info see DIRSERVER-1528
@CreateDS(enableChangeLog = false, name = "PasswordPolicyTest")
public class PasswordPolicyTest extends AbstractLdapTestUnit
{
    private PasswordPolicyConfiguration policyConfig;

    private static final ILdapCodecService codec = new DefaultLdapCodecService();
    
    private static final PasswordPolicyDecorator PP_REQ_CTRL = 
        new PasswordPolicyDecorator( codec, new PasswordPolicyImpl() );


    @Before
    public void setPwdPolicy() throws LdapException
    {
        policyConfig = new PasswordPolicyConfiguration();

        policyConfig.setPwdMaxAge( 110 );
        policyConfig.setPwdFailureCountInterval( 30 );
        policyConfig.setPwdMaxFailure( 2 );
        policyConfig.setPwdLockout( true );
        policyConfig.setPwdLockoutDuration( 0 );
        policyConfig.setPwdMinLength( 5 );
        policyConfig.setPwdInHistory( 5 );
        policyConfig.setPwdExpireWarning( 600 );
        policyConfig.setPwdGraceAuthNLimit( 5 );
        policyConfig.setPwdCheckQuality( 2 ); // DO NOT allow the password if its quality can't be checked

        PpolicyConfigContainer policyContainer = new PpolicyConfigContainer();
        policyContainer.setDefaultPolicy( policyConfig );
        service.setPwdPolicies( policyContainer );
        
        AuthenticationInterceptor authInterceptor = ( AuthenticationInterceptor ) service
        .getInterceptor( AuthenticationInterceptor.class.getName() );
        
        authInterceptor.loadPwdPolicyStateAtributeTypes();
    }


    @After
    public void closeConnections()
    {
        IntegrationUtils.closeConnections();
    }


    @Test
    public void testAddUserWithClearTextPwd() throws Exception
    {
        LdapConnection connection = getAdminNetworkConnection( ldapServer );
        
        Dn userDn = new Dn( "cn=user,ou=system" );
        Entry userEntry = LdifUtils.createEntry( userDn, "ObjectClass: top", "ObjectClass: person", "cn: user",
            "sn: user_sn", "userPassword: 1234" );

        AddRequest addRequest = new AddRequestImpl();
        addRequest.setEntry( userEntry );
        addRequest.addControl( PP_REQ_CTRL );

        AddResponse addResp = connection.add( addRequest );
        assertEquals( ResultCodeEnum.CONSTRAINT_VIOLATION, addResp.getLdapResult().getResultCode() );

        PasswordPolicy respCtrl = getPwdRespCtrl( addResp );
        assertNotNull( respCtrl );
        assertEquals( PASSWORD_TOO_SHORT, respCtrl.getResponse().getPasswordPolicyError() );

        EntryAttribute pwdAt = userEntry.get( SchemaConstants.USER_PASSWORD_AT );
        pwdAt.clear();
        pwdAt.add( "12345" );

        addResp = connection.add( addRequest );
        assertEquals( ResultCodeEnum.SUCCESS, addResp.getLdapResult().getResultCode() );
        respCtrl = getPwdRespCtrl( addResp );
        assertNull( respCtrl );

        LdapConnection userConnection = getNetworkConnectionAs( ldapServer, userDn.getName(), "12345" );
        assertNotNull( userConnection );
        assertTrue( userConnection.isAuthenticated() );
    }


    @Test
    public void testAddUserWithHashedPwd() throws Exception
    {
        LdapConnection connection = getAdminNetworkConnection( ldapServer );

        byte[] password = PasswordUtil.createStoragePassword( "12345", LdapSecurityConstants.HASH_METHOD_CRYPT );

        Dn userDn = new Dn( "cn=hashedpwd,ou=system" );
        Entry userEntry = new DefaultEntry( userDn );
        userEntry.add( SchemaConstants.OBJECT_CLASS, SchemaConstants.PERSON_OC );
        userEntry.add( SchemaConstants.CN_AT, "hashedpwd" );
        userEntry.add( SchemaConstants.SN_AT, "hashedpwd_sn" );
        userEntry.add( SchemaConstants.USER_PASSWORD_AT, password );

        AddRequest addRequest = new AddRequestImpl();
        addRequest.setEntry( userEntry );
        addRequest.addControl( PP_REQ_CTRL );

        AddResponse addResp = connection.add( addRequest );
        assertEquals( ResultCodeEnum.CONSTRAINT_VIOLATION, addResp.getLdapResult().getResultCode() );

        PasswordPolicy respCtrl = getPwdRespCtrl( addResp );
        assertNotNull( respCtrl );
        assertEquals( INSUFFICIENT_PASSWORD_QUALITY, respCtrl.getResponse().getPasswordPolicyError() );

        policyConfig.setPwdCheckQuality( 1 ); // allow the password if its quality can't be checked
        EntryAttribute pwdAt = userEntry.get( SchemaConstants.USER_PASSWORD_AT );
        pwdAt.clear();
        pwdAt.add( password );

        addResp = connection.add( addRequest );
        assertEquals( ResultCodeEnum.SUCCESS, addResp.getLdapResult().getResultCode() );
        respCtrl = getPwdRespCtrl( addResp );
        assertNull( respCtrl );

        LdapConnection userConnection = getNetworkConnectionAs( ldapServer, userDn.getName(), "12345" );
        assertNotNull( userConnection );
        assertTrue( userConnection.isAuthenticated() );
    }


    @Test
    public void testPwdMinAge() throws Exception
    {
        policyConfig.setPwdMinAge( 5 );

        LdapConnection connection = getAdminNetworkConnection( ldapServer );

        Dn userDn = new Dn( "cn=userMinAge,ou=system" );
        Entry userEntry = LdifUtils.createEntry(userDn, "ObjectClass: top", "ObjectClass: person", "cn: userMinAge",
                "sn: userMinAge_sn", "userPassword: 12345");

        AddRequest addRequest = new AddRequestImpl();
        addRequest.setEntry( userEntry );
        addRequest.addControl( PP_REQ_CTRL );

        AddResponse addResp = connection.add( addRequest );
        assertEquals( ResultCodeEnum.SUCCESS, addResp.getLdapResult().getResultCode() );

        PasswordPolicy respCtrl = getPwdRespCtrl( addResp );
        assertNull( respCtrl );

        ModifyRequest modReq = new ModifyRequestImpl();
        modReq.setName( userDn );
        modReq.addControl( PP_REQ_CTRL );
        modReq.replace( SchemaConstants.USER_PASSWORD_AT, "123456" );

        ModifyResponse modResp = connection.modify( modReq );
        assertEquals( ResultCodeEnum.CONSTRAINT_VIOLATION, modResp.getLdapResult().getResultCode() );

        respCtrl = getPwdRespCtrl( modResp );
        assertEquals( PASSWORD_TOO_YOUNG, respCtrl.getResponse().getPasswordPolicyError() );

        Thread.sleep( 5000 );

        modResp = connection.modify( modReq );
        assertEquals( ResultCodeEnum.SUCCESS, modResp.getLdapResult().getResultCode() );

        LdapConnection userConnection = getNetworkConnectionAs( ldapServer, userDn.getName(), "123456" );
        assertNotNull( userConnection );
        assertTrue( userConnection.isAuthenticated() );
    }


    private PasswordPolicy getPwdRespCtrl( Response resp ) throws Exception
    {
        ICodecControl<? extends Control> ctrl = codec.newControl( resp.getControls().get( PP_REQ_CTRL.getOid() ) );

        if ( ctrl == null )
        {
            return null;
        }

        PasswordPolicyDecorator respCtrl = new PasswordPolicyDecorator( codec );
        respCtrl.setValue( ctrl.getValue() );
        return respCtrl;
    }
}
