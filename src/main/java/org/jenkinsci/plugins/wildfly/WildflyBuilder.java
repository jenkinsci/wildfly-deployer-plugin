/**
 * The MIT License
 * 
 * Copyright (c) 2015, Dan B. Dillingham
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE. 
 */

package org.jenkinsci.plugins.wildfly;

import static org.apache.commons.lang.StringUtils.trim;

import jenkins.model.Jenkins;
import org.jenkinsci.remoting.RoleChecker;

import hudson.Launcher;
import hudson.Extension;
import hudson.FilePath;
import hudson.EnvVars;
import hudson.util.FormValidation;
import hudson.model.AbstractBuild;
import hudson.FilePath.FileCallable;
import hudson.model.BuildListener;
import hudson.model.AbstractProject;
import hudson.remoting.VirtualChannel;
import hudson.tasks.Builder;
import hudson.tasks.BuildStepDescriptor;
import hudson.model.TopLevelItem;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import java.io.IOException;
import java.io.File;

import org.jboss.as.cli.scriptsupport.*;
import org.jboss.dmr.ModelNode;

/**
 * @author Dan B. Dillingham
 *
 */
public class WildflyBuilder extends Builder {

    private final String war;
    private final String host;
    private final String port;
    private final String username;
    private final String password;
    private final String server;

    @DataBoundConstructor
    public WildflyBuilder(String war, String host, String port, String username, String password, String server) {
        this.war = trim(war);
        this.host = trim(host);
        this.port = trim(port);
        this.username = trim(username);
        this.password = trim(password);
        this.server = trim(server);
    }

    public String getWar() {
        return war;
    }
    
    public String getHost() {
        return host;
    }
    
    public String getPort() {
    	return port;
    }
    
    public String getUsername() {
    	return username;
    }    
    
    public String getPassword() {
    	return password;
    }
    
    public String getServer() {
    	return server;
    }

    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) {
           	    
    	char[] passwordAsCharArray;
    	CLI.Result result;
    	String warPath, warFilename, response, localPath, remotePath;
    	FilePath localFP = null;
    	
    	try {
    		int portAsInt = Integer.parseInt(port);
                
                final String expandedWar = expandParameter(war, build, listener);                
    		   				    		
    		FilePath fp = new FilePath(build.getWorkspace(), expandedWar);
    		remotePath=fp.getRemote();
    		if (! fp.exists()) {
    			listener.fatalError("The '"+expandedWar+"' file does not exist in workspace.");
    			return false;
    		}	    			
    		 		
    		// if running on a remote slave, copy the WAR file to the master so CLI (which must run on the master) can access it
    		if (fp.isRemote()) {
    			localPath = Jenkins.getInstance().getWorkspaceFor((TopLevelItem)build.getProject().getRootProject()).getRemote();     		
    			localPath=localPath.concat("/"+expandedWar);
    			localFP = new FilePath(new File(localPath));
    			fp.act(new GrabWARFile(localFP));
    			warPath = localPath;
    		} else 
    			warPath = remotePath;
            
                final String expandedHost = expandParameter(host, build, listener);
    		
        	CLI cli = CLI.newInstance();   
        	if (username.length() > 0) {
        		passwordAsCharArray = password.toCharArray();
        		cli.connect(expandedHost, portAsInt, username, passwordAsCharArray);
        	} else
        		cli.connect(expandedHost, portAsInt, null, null);
        	
    		listener.getLogger().println("Connected to WildFly at "+expandedHost+":"+port); 		
    		
        	int idx=expandedWar.lastIndexOf("/");
        	if (idx > 0) {
        		warFilename = expandedWar.substring(idx+1, expandedWar.length());
        	} else {
        		warFilename = expandedWar;
        	}
                
                final String expandedServer = expandParameter(server, build, listener);
        	
    		// if application exists, undeploy it first...
    		if (applicationExists(cli, warFilename, expandedServer)) {
    			listener.getLogger().println("Application "+warFilename+" exists, undeploying...");
    			if (server.length() > 0)
    				result = cli.cmd("undeploy "+warFilename+" --server-groups="+expandedServer);
    			else
    				result = cli.cmd("undeploy "+warFilename);
    			response = getWildFlyResponse(result);
    			if (response.indexOf("{\"outcome\" => \"failed\"") >= 0) {
        			listener.fatalError(response);
        			return false;
        		} else
            		listener.getLogger().println(response);
    		}

    		listener.getLogger().println("Deploying "+warFilename+" ...");
    		if (server.length() > 0)
    			result = cli.cmd("deploy "+warPath+" --server-groups="+expandedServer);
    		else
    			result = cli.cmd("deploy "+warPath);
    		
    		response = getWildFlyResponse(result);   		
    		if (response.indexOf("{\"outcome\" => \"failed\"") >= 0) {
    			listener.fatalError(response);
    			return false;
    		} else
        		listener.getLogger().println(response);
        	
        	cli.disconnect();    	
        	
        	if (localFP != null)
        		localFP.delete();
    	
    	} catch (Exception e) {
    		e.printStackTrace();
    		listener.fatalError(e.getMessage());
    		return false;
    	}
    	    	
        return true;
        
    }
       
    private boolean applicationExists(CLI cli, String war, String server) {
  
    	CLI.Result result;
    	String     response;
    	
    	if (server.length() > 0) {
    		result = cli.cmd("deployment-info --server-group="+server);
    	} else {
    		result = cli.cmd("deployment-info");	
    	}
    	
    	response = getWildFlyResponse(result);
    	 	
    	if (response.indexOf(war) < 0)
    		return false;
    	else 
    		return true;
    }
    
    private String getWildFlyResponse(CLI.Result result) {
    		
       	ModelNode modelNode = result.getResponse();
    	String response = modelNode.asString(); 
    	
    	return response;
    }
    
    private String expandParameter(String parameter, AbstractBuild build, BuildListener listener) throws IOException, InterruptedException {
        if (parameter.indexOf('$') > -1) {
            final EnvVars env = build.getEnvironment(listener);
            return env.expand(parameter);
        }

        return parameter;
    }
       
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    private static final class GrabWARFile implements FileCallable<Void> {
        private static final long serialVersionUID = 1;
        
        private final FilePath fp;

        public GrabWARFile(FilePath fp) {
        	this.fp = fp;
        }

        @Override 
        public Void invoke(File f, VirtualChannel channel) {

        	try {
        		FilePath remoteFilePath = new FilePath(f);
        		fp.copyFrom(remoteFilePath);       		
        	} catch (Exception e) {
        		return null;
        	}

            return null;
        }
        
        @Override
        public void checkRoles(RoleChecker checker) throws SecurityException {
        	
        }
        
    }
    
    @Extension 
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        public DescriptorImpl() {
            load();
        }

        public FormValidation doCheckWar(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please specify a WAR or EAR filename.");
            if (! value.toLowerCase().contains(".war".toLowerCase()) && ! value.toLowerCase().contains(".ear".toLowerCase()))  
            	return FormValidation.error("Please specify a valid WAR or EAR filename."); 
            if (value.length() < 7)
                return FormValidation.warning("Is that a valid WAR or EAR filename?");
            return FormValidation.ok();
        }
        
        public FormValidation doCheckHost(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please specify a valid hostname.");          
            if (value.length() < 4)
                return FormValidation.warning("Is that a valid hostname?");
            return FormValidation.ok();
        }
        
        public FormValidation doCheckPort(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0 || value.length() < 4 || value.length() > 5 || ! value.matches("[0-9]+"))
                return FormValidation.error("Please specify a valid port number.");  
            return FormValidation.ok();
        }        
        
        public FormValidation doCheckPassword(@QueryParameter String value, @QueryParameter String username)
                throws IOException, ServletException {
            if (value.length() != 0)
            	if (username.length() == 0)
            		return FormValidation.error("Both a user name and a password must be specified.");  
            return FormValidation.ok();
        }  
        
        public FormValidation doCheckUsername(@QueryParameter String value, @QueryParameter String password)
                throws IOException, ServletException {
            if (value.length() != 0)
            	if (password.length() == 0)
            		return FormValidation.error("Both a user name and a password must be specified.");  
            return FormValidation.ok();
        } 
        
        public FormValidation doCheckServer(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() > 0 && value.length() < 5)
                return FormValidation.warning("Is that a valid server group or standalone server name?");     
            return FormValidation.ok();
        }         

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        public String getDisplayName() {
            return "Deploy WAR/EAR to WildFly";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            save();
            return super.configure(req,formData);
        }
        
    }
    
}

