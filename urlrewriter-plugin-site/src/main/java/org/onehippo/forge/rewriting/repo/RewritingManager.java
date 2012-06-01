/**
 * Copyright (C) 2011 Hippo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onehippo.forge.rewriting.repo;

import java.util.Date;
import java.util.List;

import javax.jcr.Credentials;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Repository;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.observation.Event;
import javax.servlet.ServletContext;
import javax.servlet.ServletRequest;

import org.hippoecm.repository.api.HippoNodeType;
import org.onehippo.forge.rewriting.UrlRewriteConstants;
import org.onehippo.forge.rewriting.UrlRewriteUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tuckey.web.filters.urlrewrite.utils.StringUtils;

/**
 * @version $Id$
 */
public class RewritingManager {

    private static Logger log = LoggerFactory.getLogger(RewritingManager.class);

    // spring managed
    private String rewriteRulesLocation;
    private Repository repository;
    private Credentials credentials;
    private List<RewritingRulesExtractor> rewritingRulesExtractors;

    // default, no rules
    private StringBuilder loadedRules = new StringBuilder();
    private Date lastLoadDate = new Date();

    private volatile boolean needRefresh = true;

    public boolean needReloading() {
        return needRefresh;
    }

    /**
     * Load rules for given path. If no path is provided, default will be used (configured in spring configuration)
     *
     * @param context
     * @param request
     * @param urlRewriteLocation absolute repository path
     */
    @Deprecated
    public synchronized StringBuilder loadRules(final ServletContext context, final ServletRequest request, final String urlRewriteLocation) {
        return load(context, request, null);
    }


    /**
    * Load rules for given path. If no path is provided, default will be used (configured in spring configuration)
    *
    * @param context
    * @param request
    * @param rewriteRulesLocation absolute repository path of the rules
    */
    public synchronized StringBuilder load(final ServletContext context, final ServletRequest request, final String rewriteRulesLocation) {
        // check if refresh is needed..if not return local copy
        if (!needRefresh) {
            return loadedRules;
        }

        String rulesLocation = getRulesLocation(rewriteRulesLocation);
        if(StringUtils.isBlank(rulesLocation)){
            log.error("No location specified for rules. Cannot load rules.");
            return null;
        }

        log.debug("Loading rules from location {}", rulesLocation);

        Session session = null;
        StringBuilder rules = null;
        try {
            session = getSession();
            if (session == null) {
                return null;
            }
            Node rootNode = session.getRootNode().getNode(rulesLocation.substring(1));
            if(! rootNode.hasNodes()){
                log.debug("No rules found under {}.", UrlRewriteUtils.getJcrItemPath(rootNode));
                return null;
            }

            boolean ignoreContextPath = rootNode.hasProperty(UrlRewriteConstants.IGNORE_CONTEXT_PATH_PROPERTY) ?
                    Boolean.valueOf(rootNode.getProperty(UrlRewriteConstants.IGNORE_CONTEXT_PATH_PROPERTY).getString()) :
                    UrlRewriteConstants.IGNORE_CONTEXT_PATH_PROPERTY_DEFAULT_VALUE;

            //Start recursion
            rules = new StringBuilder(UrlRewriteConstants.XML_START);
            load(rootNode, context, rules, ignoreContextPath);

        } catch (Exception e) {
            log.error("Error loading simple rewriting rules {}", e);
        } finally {
            closeSession(session);
        }

        rules.append(UrlRewriteConstants.XML_END);

        //Update our state
        loadedRules = new StringBuilder(rules);
        needRefresh = false;
        lastLoadDate = new Date();

        return rules;
    }

    /**
     * Load rules recursively starting from a urlrewriter:ruleset node
     *
     * @param startNode
     * @param context
     * @param rules StringBuilder to load the rules in
     */
    private void load(final Node startNode, final ServletContext context, final StringBuilder rules, final boolean ignoreContextPath) throws RepositoryException {

        NodeIterator nodes = startNode.getNodes();
        while (nodes.hasNext()) {
            Node node = nodes.nextNode();
            if(node.isNodeType(UrlRewriteConstants.PRIMARY_TYPE_RULESET)){
                load(node, context, rules, ignoreContextPath);
            } else {
                node = getDocumentNode(node);
                if(node == null){
                    continue;
                }

                String rule = null;
                for(RewritingRulesExtractor rulesExtractor : rewritingRulesExtractors){
                    try{
                        rule = rulesExtractor.extract(node, context, ignoreContextPath);
                        if(rule != null){
                            rules.append(rule);
                        }
                    } catch (RepositoryException e){
                        log.error("Exception encountered while extracting with: {}", rulesExtractor);
                        log.error("Exception is: ", e);
                    }
                }
            }
        }
    }


    protected Node getDocumentNode(Node wrapperNode) throws RepositoryException{
        Node document = null;
        if (wrapperNode.isNodeType(HippoNodeType.NT_HANDLE)) {
            NodeIterator docs = wrapperNode.getNodes(wrapperNode.getName());
            while (docs.hasNext()) {
                document = docs.nextNode();
                //TODO Replace when the constant is added to the api
                if (document.isNodeType("hippostd:publishable")) {
                    String state = document.getProperty("hippostd:state").getString();
                    if ("published".equals(state)) {
                        return document;
                    }
                }
            }
        }
        return null;
    }


    protected String getRulesLocation(String overrideRulesLocation){
        String rulesLocation = overrideRulesLocation;
        if(StringUtils.isBlank(rulesLocation)){
            log.debug("Filter configuration does not specify UrlRewriteLocation, or is not an absolute path. Will use default one: {}", getRewriteRulesLocation());
            rulesLocation = getRewriteRulesLocation();
            if (rulesLocation == null || !rulesLocation.startsWith("/")) {
                return null;
            }
        }
        return rulesLocation;
    }

    public void invalidate(final Event event) {
        needRefresh = true;
    }

    public Date getLastLoadDate() {
        return lastLoadDate;
    }

    private Session getSession() {
        Session session = null;
        try {
            session = repository.login(credentials);
        } catch (RepositoryException e) {
            log.error("Error obtaining session {}", e);
        }
        return session;
    }

    private void closeSession(final Session session) {
        if (session != null) {
            session.logout();
        }
    }



    //*************************************************************************************
    // SPRING MANAGED PROPERTIES
    //*************************************************************************************


    public String getRewriteRulesLocation() {
        return rewriteRulesLocation;
    }

    public void setRewriteRulesLocation(final String rewriteRulesLocation) {
        this.rewriteRulesLocation = rewriteRulesLocation;
    }

    public Repository getRepository() {
        return repository;
    }

    public void setRepository(final Repository repository) {
        this.repository = repository;
    }

    public Credentials getCredentials() {
        return credentials;
    }

    public void setCredentials(final Credentials credentials) {
        this.credentials = credentials;
    }

    public List<RewritingRulesExtractor> getRewritingRulesExtractors() {
        return rewritingRulesExtractors;
    }

    public void setRewritingRulesExtractors(final List<RewritingRulesExtractor> rewritingRulesExtractors) {
        this.rewritingRulesExtractors = rewritingRulesExtractors;
    }



}
