/*
 *
 *  *  Copyright 2016 Orient Technologies LTD (info(at)orientechnologies.com)
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *  * For more information: http://www.orientechnologies.com
 *
 */
package com.orientechnologies.orient.server.security;

import javax.security.auth.Subject;

import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.server.OServer;
import com.orientechnologies.orient.server.config.OServerConfigurationManager;
import com.orientechnologies.orient.server.config.OServerUserConfiguration;
import com.orientechnologies.orient.server.security.OSecurityComponent;

/**
 * Provides an abstract implementation of OSecurityAuthenticator.
 * 
 * @author S. Colin Leister
 * 
 */
public abstract class OSecurityAuthenticatorAbstract implements OSecurityAuthenticator
{
	private String _Name = "";
	private boolean _Debug = false;
	private boolean _Enabled = true;
	private OServer _Server;
	private OServerConfigurationManager _ServerConfig;
	
	protected OServer getServer() { return _Server; }
	protected OServerConfigurationManager getServerConfig() { return _ServerConfig; }
	protected boolean isDebug() { return _Debug; }
	
	// OSecurityComponent
	public void active() { }
	
	// OSecurityComponent
	public void config(final OServer oServer, final OServerConfigurationManager serverCfg, final ODocument jsonConfig)
	{
		_Server = oServer;
		_ServerConfig = serverCfg;
		
		if(jsonConfig.containsField("name"))
		{
			_Name = jsonConfig.field("name");
		}
		
		if(jsonConfig.containsField("debug"))
		{
			_Debug = jsonConfig.field("debug");
		}		

		if(jsonConfig.containsField("enabled"))
		{
			_Enabled = jsonConfig.field("enabled");
		}		
	}

	// OSecurityComponent
	public void dispose() { }

	// OSecurityComponent
	public boolean isEnabled() { return _Enabled; }
	
	// OSecurityAuthenticator
	// databaseName may be null.
	public String getAuthenticationHeader(String databaseName)
	{
		String header;
		
		// Default to Basic.
		if(databaseName != null) header = "WWW-Authenticate: Basic realm=\"OrientDB db-" + databaseName + "\"";
		else header = "WWW-Authenticate: Basic realm=\"OrientDB Server\"";
		
		return header;
	}
	
	public Subject getClientSubject() { return null; }
	
	// Returns the name of this OSecurityAuthenticator.
	public String getName() { return _Name; }
	
	public OServerUserConfiguration getUser(final String username) { return null; }

	public boolean isAuthorized(final String username, final String resource) { return false; }

/*
	// Reloads the security authenticator, using the new configuration document.
	public void reload(final ODocument jsonConfig)
	{
		dispose();
		
		config(_Server, _ServerConfig, jsonConfig);
		
		active();
	}
*/	
	public boolean isSingleSignOnSupported() { return false; }
}