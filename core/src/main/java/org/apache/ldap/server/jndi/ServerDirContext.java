/*
 *   Copyright 2004 The Apache Software Foundation
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 */
package org.apache.ldap.server.jndi;


import java.io.IOException;
import java.io.Serializable;
import java.text.ParseException;
import java.util.Hashtable;

import javax.naming.Name;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.Reference;
import javax.naming.Referenceable;
import javax.naming.event.EventDirContext;
import javax.naming.event.NamingListener;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InvalidSearchFilterException;
import javax.naming.directory.ModificationItem;
import javax.naming.directory.SearchControls;
import javax.naming.ldap.Control;
import javax.naming.spi.DirStateFactory;
import javax.naming.spi.DirectoryManager;

import org.apache.ldap.common.filter.BranchNode;
import org.apache.ldap.common.filter.ExprNode;
import org.apache.ldap.common.filter.FilterParser;
import org.apache.ldap.common.filter.FilterParserImpl;
import org.apache.ldap.common.filter.PresenceNode;
import org.apache.ldap.common.filter.SimpleNode;
import org.apache.ldap.common.name.LdapName;
import org.apache.ldap.common.util.NamespaceTools;
import org.apache.ldap.server.authn.LdapPrincipal;
import org.apache.ldap.server.partition.ContextPartitionNexus;


/**
 * The DirContext implementation for the Server Side JNDI LDAP provider.
 *
 * @author <a href="mailto:dev@directory.apache.org">Apache Directory Project</a>
 * @version $Rev$
 */
public abstract class ServerDirContext extends ServerContext implements EventDirContext
{
    
    
    // ------------------------------------------------------------------------
    // Constructors
    // ------------------------------------------------------------------------
    
    /**
     * Creates a new ServerDirContext by reading the PROVIDER_URL to resolve the
     * distinguished name for this context.
     *
     * @param service the parent service that manages this context
     * @param env the environment used for this context
     * @throws NamingException if something goes wrong
     */
    public ServerDirContext( ContextFactoryService service, Hashtable env ) throws NamingException
    {
        super( service, env );
    }


    /**
     * Creates a new ServerDirContext with a distinguished name which is used to
     * set the PROVIDER_URL to the distinguished name for this context.
     *
     * @param principal the principal which is propagated
     * @param nexusProxy the intercepting proxy to the nexus
     * @param env the environment properties used by this context
     * @param dn the distinguished name of this context
     */
    protected ServerDirContext( LdapPrincipal principal, ContextPartitionNexus nexusProxy, Hashtable env, Name dn )
    {
        super( principal, nexusProxy, env, dn );
    }


    // ------------------------------------------------------------------------
    // DirContext Implementations
    // ------------------------------------------------------------------------


    /**
     * @see javax.naming.directory.DirContext#getAttributes(java.lang.String)
     */
    public Attributes getAttributes( String name ) throws NamingException
    {
        return getAttributes( new LdapName( name ) );
    }
    

    /**
     * @see javax.naming.directory.DirContext#getAttributes(javax.naming.Name)
     */
    public Attributes getAttributes( Name name ) throws NamingException
    {
        return getNexusProxy().lookup( buildTarget( name ) );
    }


    /**
     * @see javax.naming.directory.DirContext#getAttributes(java.lang.String,
     *      java.lang.String[])
     */
    public Attributes getAttributes( String name, String[] attrIds ) throws NamingException
    {
        return getAttributes( new LdapName( name ), attrIds );
    }


    /**
     * @see javax.naming.directory.DirContext#getAttributes(javax.naming.Name,
     *      java.lang.String[])
     */
    public Attributes getAttributes( Name name, String[] attrIds ) throws NamingException
    {
        return getNexusProxy().lookup( buildTarget( name ), attrIds );
    }
    

    /**
     * @see javax.naming.directory.DirContext#modifyAttributes(java.lang.String,
     *      int, javax.naming.directory.Attributes)
     */
    public void modifyAttributes( String name, int modOp, Attributes attrs ) throws NamingException
    {
        modifyAttributes( new LdapName( name ), modOp, attrs );
    }


    /**
     * @see javax.naming.directory.DirContext#modifyAttributes(
     * javax.naming.Name,int, javax.naming.directory.Attributes)
     */
    public void modifyAttributes( Name name, int modOp, Attributes attrs ) throws NamingException
    {
        getNexusProxy().modify( buildTarget( name ), modOp, attrs );
    }


    /**
     * @see javax.naming.directory.DirContext#modifyAttributes(java.lang.String,
     *      javax.naming.directory.ModificationItem[])
     */
    public void modifyAttributes( String name, ModificationItem[] mods ) throws NamingException
    {
        modifyAttributes( new LdapName( name ), mods );
    }


    /**
     * @see javax.naming.directory.DirContext#modifyAttributes(
     * javax.naming.Name, javax.naming.directory.ModificationItem[])
     */
    public void modifyAttributes( Name name, ModificationItem[] mods ) throws NamingException
    {
        getNexusProxy().modify( buildTarget( name ), mods );
    }
    

    /**
     * @see javax.naming.directory.DirContext#bind(java.lang.String,
     *      java.lang.Object, javax.naming.directory.Attributes)
     */
    public void bind( String name, Object obj, Attributes attrs ) throws NamingException
    {
        bind( new LdapName( name ), obj, attrs );
    }


    /**
     * @see javax.naming.directory.DirContext#bind(javax.naming.Name,
     *      java.lang.Object, javax.naming.directory.Attributes)
     */
    public void bind( Name name, Object obj, Attributes attrs ) throws NamingException
    {
        if ( null == obj && null == attrs )
        {
            throw new NamingException( "Both obj and attrs args are null. "
                + "At least one of these parameters must not be null." );
        }

        // A null attrs defaults this to the Context.bind() operation
        if ( null == attrs )
        {
            super.bind( name, obj );

            return;
        }

        // No object binding so we just add the attributes
        if ( null == obj )
        {
            Attributes clone = ( Attributes ) attrs.clone();

            Name target = buildTarget( name );

            getNexusProxy().add( target.toString(), target, clone );

            return;
        }

        // First, use state factories to do a transformation
        DirStateFactory.Result res = DirectoryManager.getStateToBind( obj, name, this, getEnvironment(), attrs );

        Attributes outAttrs = res.getAttributes();

        if ( outAttrs != attrs )
        {
            Name target = buildTarget( name );

            Attributes attributes = ( Attributes ) attrs.clone();

            if ( outAttrs != null && outAttrs.size() > 0 )
            {
                NamingEnumeration list = outAttrs.getAll();

                while ( list.hasMore() )
                {
                    attributes.put( ( Attribute ) list.next() );
                }
            }

            getNexusProxy().add( target.toString(), target, attributes );

            return;
        }

        // Check for Referenceable
        if ( obj instanceof Referenceable )
        {
            obj = ( ( Referenceable ) obj ).getReference();

            throw new NamingException( "Do not know how to store Referenceables yet!" );
        }

        // Store different formats
        if ( obj instanceof Reference )
        {
            // Store as ref and add outAttrs

            throw new NamingException( "Do not know how to store References yet!" );
        }
        else if ( obj instanceof Serializable )
        {
            // Serialize and add outAttrs

            Attributes attributes = ( Attributes ) attrs.clone();

            if ( outAttrs != null && outAttrs.size() > 0 )
            {
                NamingEnumeration list = outAttrs.getAll();

                while ( list.hasMore() )
                {
                    attributes.put( ( Attribute ) list.next() );
                }
            }

            Name target = buildTarget( name );

            // Serialize object into entry attributes and add it.

            JavaLdapSupport.serialize( attributes, obj );

            getNexusProxy().add( target.toString(), target, attributes );
        }
        else if ( obj instanceof DirContext )
        {
            // Grab attributes and merge with outAttrs

            Attributes attributes = ( ( DirContext ) obj ).getAttributes( "" );

            if ( outAttrs != null && outAttrs.size() > 0 )
            {
                NamingEnumeration list = outAttrs.getAll();

                while ( list.hasMore() )
                {
                    attributes.put( ( Attribute ) list.next() );
                }
            }

            Name target = buildTarget( name );

            getNexusProxy().add( target.toString(), target, attributes );
        }
        else
        {
            throw new NamingException( "Can't find a way to bind: " + obj );
        }
    }


    /**
     * @see javax.naming.directory.DirContext#rebind(java.lang.String,
     *      java.lang.Object, javax.naming.directory.Attributes)
     */
    public void rebind( String name, Object obj, Attributes attrs ) throws NamingException
    {
        rebind( new LdapName( name ), obj, attrs );
    }


    /**
     * @see javax.naming.directory.DirContext#rebind(javax.naming.Name,
     *      java.lang.Object, javax.naming.directory.Attributes)
     */
    public void rebind( Name name, Object obj, Attributes attrs ) throws NamingException
    {
        Name target = buildTarget( name );

        if ( getNexusProxy().hasEntry( target ) )
        {
            getNexusProxy().delete( target );
        }

        bind( name, obj, attrs );
    }


    /**
     * @see javax.naming.directory.DirContext#createSubcontext(java.lang.String,
     *      javax.naming.directory.Attributes)
     */
    public DirContext createSubcontext( String name, Attributes attrs ) throws NamingException
    {
        return createSubcontext( new LdapName( name ), attrs );
    }


    /**
     * @see javax.naming.directory.DirContext#createSubcontext(
     * javax.naming.Name, javax.naming.directory.Attributes)
     */
    public DirContext createSubcontext( Name name, Attributes attrs ) throws NamingException
    {
        if ( null == attrs )
        {
            return ( DirContext ) super.createSubcontext( name );
        }

        LdapName target = buildTarget( name );

        String rdn = name.get( name.size() - 1 );

        String rdnAttribute = NamespaceTools.getRdnAttribute( rdn );

        String rdnValue = NamespaceTools.getRdnValue( rdn );

        // Clone the attributes and add the Rdn attributes
        Attributes attributes = ( Attributes ) attrs.clone();

        boolean doRdnPut = attributes.get( rdnAttribute ) == null;

        doRdnPut = doRdnPut || attributes.get( rdnAttribute ).size() == 0;

        doRdnPut = doRdnPut || ! attributes.get( rdnAttribute ).contains( rdnValue );

        if ( doRdnPut )
        {
            attributes.put( rdnAttribute, rdnValue );
        }

        // Add the new context to the server which as a side effect adds
        getNexusProxy().add( target.toString(), target, attributes );

        // Initialize the new context
        ServerLdapContext ctx = new ServerLdapContext( getPrincipal(), getNexusProxy(), getEnvironment(), target );

        Control [] controls = ( ( ServerLdapContext ) this ).getRequestControls();

        if ( controls != null )
        {
        	controls = ( Control[] ) controls.clone();
        }
        else
        {
        	controls = new Control[0];
        }

        ctx.setRequestControls( controls );

        return ctx;
    }


    /**
     * Presently unsupported operation!
     */
    public DirContext getSchema( Name name ) throws NamingException
    {
        throw new UnsupportedOperationException();
    }


    /**
     * Presently unsupported operation!
     */
    public DirContext getSchema( String name ) throws NamingException
    {
        throw new UnsupportedOperationException();
    }


    /**
     * Presently unsupported operation!
     */
    public DirContext getSchemaClassDefinition( Name name ) throws NamingException
    {
        throw new UnsupportedOperationException();
    }


    /**
     * Presently unsupported operation!
     */
    public DirContext getSchemaClassDefinition( String name ) throws NamingException
    {
        throw new UnsupportedOperationException();
    }


    // ------------------------------------------------------------------------
    // Search Operation Implementations
    // ------------------------------------------------------------------------


    /**
     * @see javax.naming.directory.DirContext#search(java.lang.String,
     *      javax.naming.directory.Attributes)
     */
    public NamingEnumeration search( String name, Attributes matchingAttributes )
            throws NamingException
    {
        return search( new LdapName( name ), matchingAttributes, null );
    }


    /**
     * @see javax.naming.directory.DirContext#search(javax.naming.Name,
     *      javax.naming.directory.Attributes)
     */
    public NamingEnumeration search( Name name, Attributes matchingAttributes )
            throws NamingException
    {
        return search( name, matchingAttributes, null );
    }


    /**
     * @see javax.naming.directory.DirContext#search(java.lang.String,
     *      javax.naming.directory.Attributes, java.lang.String[])
     */
    public NamingEnumeration search( String name, Attributes matchingAttributes, String[] attributesToReturn ) throws NamingException
    {
        return search( new LdapName( name ), matchingAttributes, attributesToReturn );
    }


    /**
     * @see javax.naming.directory.DirContext#search(javax.naming.Name,
     *      javax.naming.directory.Attributes, java.lang.String[])
     */
    public NamingEnumeration search( Name name, Attributes matchingAttributes, String[] attributesToReturn ) throws NamingException
    {
        SearchControls ctls = new SearchControls();

        LdapName target = buildTarget( name );

        // If we need to return specific attributes add em to the SearchControls
        if ( null != attributesToReturn )
        {
            ctls.setReturningAttributes( attributesToReturn );
        } 

        // If matchingAttributes is null/empty use a match for everything filter
        if ( null == matchingAttributes || matchingAttributes.size() <= 0 )
        {
            PresenceNode filter = new PresenceNode( "objectClass" );

            return getNexusProxy().search( target , getEnvironment(), filter, ctls );
        }

        /*
         * Go through the set of attributes using each attribute value pair as 
         * an attribute value assertion within one big AND filter expression.
         */
        Attribute attr = null;

        SimpleNode node = null;

        BranchNode filter = new BranchNode( BranchNode.AND );

        NamingEnumeration list = matchingAttributes.getAll();

        // Loop through each attribute value pair
        while ( list.hasMore() )
        {
            attr = ( Attribute ) list.next();
            
            /*
             * According to JNDI if an attribute in the matchingAttributes
             * list does not have any values then we match for just the presence
             * of the attribute in the entry
             */
            if ( attr.size() == 0 )
            {
                filter.addNode( new PresenceNode( attr.getID() ) );

                continue;
            }
            
            /*
             * With 1 or more value we build a set of simple nodes and add them
             * to the AND node - each attribute value pair is a simple AVA node.
             */
            for ( int ii = 0; ii < attr.size(); ii++ )
            {
                Object val = attr.get( ii );
                
                // Add simpel AVA node if its value is a String 
                if ( val instanceof String )
                {
                    node = new SimpleNode( attr.getID(), ( String ) val, SimpleNode.EQUALITY );

                    filter.addNode( node );
                }
            }
        }

        return getNexusProxy().search( target , getEnvironment(), filter, ctls );
    }


    /**
     * @see javax.naming.directory.DirContext#search(java.lang.String,
     *      java.lang.String, javax.naming.directory.SearchControls)
     */
    public NamingEnumeration search( String name, String filter, SearchControls cons )
            throws NamingException
    {
        return search( new LdapName( name ), filter, cons );
    }


    /**
     * @see javax.naming.directory.DirContext#search(javax.naming.Name,
     *      java.lang.String, javax.naming.directory.SearchControls)
     */
    public NamingEnumeration search( Name name, String filter, SearchControls cons )
            throws NamingException
    {
        ExprNode filterNode = null;

        LdapName target = buildTarget( name );

        if ( filter == null && getEnvironment().containsKey( "__filter__" ) )
        {
            filterNode = ( ExprNode ) getEnvironment().get( "__filter__" );
        }
        else
        {
            try
            {
                FilterParser parser = new FilterParserImpl();

                filterNode = parser.parse( filter );
            }
            catch ( ParseException pe )
            {
                InvalidSearchFilterException isfe =
                    new InvalidSearchFilterException (
                    "Encountered parse exception while parsing the filter: '"
                    + filter + "'" );

                isfe.setRootCause( pe );

                throw isfe;
            }
            catch ( IOException ioe )
            {
                NamingException ne = new NamingException(
                    "Parser failed with IO exception on filter: '"
                    + filter + "'" );
                ne.setRootCause( ioe );
                throw ne;
            }
        }

        return getNexusProxy().search( target , getEnvironment(), filterNode, cons );
    }


    /**
     * @see javax.naming.directory.DirContext#search(java.lang.String,
     *      java.lang.String, java.lang.Object[],
     *      javax.naming.directory.SearchControls)
     */
    public NamingEnumeration search( String name, String filterExpr,
        Object[] filterArgs, SearchControls cons ) throws NamingException
    {
        return search( new LdapName( name ), filterExpr, filterArgs, cons );
    }


    /**
     * @see javax.naming.directory.DirContext#search(javax.naming.Name,
     *      java.lang.String, java.lang.Object[],
     *      javax.naming.directory.SearchControls)
     */
    public NamingEnumeration search( Name name, String filterExpr, Object[] filterArgs, SearchControls cons ) throws NamingException
    {
        int start;
        int index;

        StringBuffer buf = new StringBuffer( filterExpr );
        
        // Scan until we hit the end of the string buffer 
        for ( int ii = 0; ii < buf.length(); ii++ )
        {
            try
            {
                // Advance until we hit the start of a variable
                while ( ii < buf.length() && '{' != buf.charAt( ii ) )
                {
                    ii++;
                }
                           
                // Record start of variable at '{'
                start = ii;
                
                // Advance to the end of a variable at '}'
                while ( '}' != buf.charAt( ii ) )
                {
                    ii++;
                }
            }
            catch (IndexOutOfBoundsException e)
            {
                // End of filter so done.
                break;
            }
            
            // Parse index
            index = Integer.parseInt(buf.substring(start + 1, ii));
            
            /*
             * Replace the '{ i }' with the string representation of the value
             * held in the filterArgs array at index index.
             */           
            buf.replace( start, ii + 1, filterArgs[index].toString() );
        }
        
        return search( name, buf.toString(), cons );
    }


    // ------------------------------------------------------------------------
    // EventDirContext implementations
    // ------------------------------------------------------------------------


    FilterParserImpl filterParser = new FilterParserImpl();

    public void addNamingListener( Name name, String filterStr, SearchControls searchControls,
                                   NamingListener namingListener )
            throws NamingException
    {
        ExprNode filter = null;

        try
        {
            filter = filterParser.parse( filterStr );
        }
        catch ( Exception e )
        {
            NamingException e2 = new NamingException( "could not parse filter: " + filterStr );
            e2.setRootCause( e );
            throw e2;
        }

        ( ( ContextPartitionNexusProxy ) getNexusProxy() )
                .addNamingListener( this, buildTarget( name ), filter, searchControls, namingListener );
        getListeners().add( namingListener );
    }


    public void addNamingListener( String name, String filter, SearchControls searchControls, NamingListener namingListener )
            throws NamingException
    {
        addNamingListener( new LdapName( name ), filter, searchControls, namingListener );
    }


    public void addNamingListener( Name name, String filterExpr, Object[] filterArgs, SearchControls searchControls,
                                   NamingListener namingListener ) throws NamingException
    {
        int start;

        StringBuffer buf = new StringBuffer( filterExpr );

        // Scan until we hit the end of the string buffer
        for ( int ii = 0; ii < buf.length(); ii++ )
        {
            // Advance until we hit the start of a variable
            while ( '{' != buf.charAt( ii ) )
            {
                ii++;
            }

            // Record start of variable at '{'
            start = ii;

            // Advance to the end of a variable at '}'
            while ( '}' != buf.charAt( ii ) )
            {
                ii++;
            }
            
            /*
             * Replace the '{ i }' with the string representation of the value
             * held in the filterArgs array at index index.
             */
            buf.replace( start, ii + 1, filterArgs[ii].toString() );
        }

        addNamingListener( name, buf.toString(), searchControls, namingListener );
    }


    public void addNamingListener( String name, String filter, Object[] objects, SearchControls searchControls,
                                   NamingListener namingListener ) throws NamingException
    {
        addNamingListener( new LdapName( name ), filter, objects, searchControls, namingListener );
    }
}
