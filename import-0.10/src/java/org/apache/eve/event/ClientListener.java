/*
 * $Id: ClientListener.java,v 1.2 2003/03/13 18:27:17 akarasulu Exp $
 *
 * -- (c) LDAPd Group                                                    --
 * -- Please refer to the LICENSE.txt file in the root directory of      --
 * -- any LDAPd project for copyright and distribution information.      --
 *
 */

package org.apache.eve.event ;

public interface ClientListener
{
    void clientAdded(ClientEvent a_client) ;
    void clientDropped(ClientEvent a_client) ;
}
