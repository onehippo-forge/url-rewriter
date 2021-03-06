/**
 * Copyright 2011-2013 Hippo B.V. (http://www.onehippo.com)
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
package org.onehippo.forge.rewriting.frontend.plugins.cms.browse.list.resolvers;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.nodetype.NodeType;
import javax.jcr.nodetype.NodeTypeManager;

import org.apache.wicket.model.IDetachable;
import org.hippoecm.frontend.model.JcrNodeModel;
import org.hippoecm.frontend.model.event.IObservable;
import org.hippoecm.frontend.model.event.IObservationContext;
import org.hippoecm.frontend.model.event.Observable;
import org.hippoecm.repository.HippoStdNodeType;
import org.hippoecm.repository.api.HippoNodeType;
import org.onehippo.repository.util.JcrConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @version $Id$
 */
public class UrlRewriterAttributes implements IObservable, IDetachable {

    private static final long serialVersionUID = 1L;

    static final Logger log = LoggerFactory.getLogger(UrlRewriterAttributes.class);

    private JcrNodeModel nodeModel;
    private Observable observable;
    private transient boolean loaded = false;

    private transient String originalUrl;
    private transient String rewriteUrl;
    private transient String rewriteType;

    public UrlRewriterAttributes(JcrNodeModel nodeModel) {
        this.nodeModel = nodeModel;
        observable = new Observable(nodeModel);
    }

    public String getOriginalUrl() {
        load();
        return originalUrl;
    }

    public String getRewriteUrl() {
        load();
        return rewriteUrl;
    }

    public String getRewriteType() {
        load();
        return rewriteType;
    }

   public void detach() {
        loaded = false;

        originalUrl = null;

        nodeModel.detach();
        observable.detach();
    }

    void load() {
        if (!loaded) {
            observable.setTarget(null);
            try {
                Node node = nodeModel.getNode();
                if (node != null) {
                    Node document = null;
                    NodeType primaryType = null;
                    if (node.isNodeType(HippoNodeType.NT_HANDLE)) {
                        NodeIterator docs = node.getNodes(node.getName());
                        while (docs.hasNext()) {
                            document = docs.nextNode();
                            primaryType = document.getPrimaryNodeType();
                            if (document.isNodeType(HippoStdNodeType.NT_PUBLISHABLE)) {
                                String state = document.getProperty(HippoStdNodeType.HIPPOSTD_STATE).getString();
                                if ("unpublished".equals(state)) {
                                    break;
                                }
                            }
                        }
                    } else if (node.isNodeType(HippoNodeType.NT_DOCUMENT)) {
                        document = node;
                        primaryType = document.getPrimaryNodeType();
                    } else if (node.isNodeType(JcrConstants.NT_VERSION)) {
                        Node frozen = node.getNode(JcrConstants.JCR_FROZEN_NODE);
                        String primary = frozen.getProperty(JcrConstants.JCR_FROZEN_PRIMARY_TYPE).getString();
                        NodeTypeManager ntMgr = frozen.getSession().getWorkspace().getNodeTypeManager();
                        primaryType = ntMgr.getNodeType(primary);
                        if (primaryType.isNodeType(HippoNodeType.NT_DOCUMENT)) {
                            document = frozen;
                        }
                    }
                    if (document != null) {
                        if (primaryType.isNodeType(HippoStdNodeType.NT_PUBLISHABLESUMMARY) || document.isNodeType(HippoStdNodeType.NT_PUBLISHABLESUMMARY)) {
                            observable.setTarget(new JcrNodeModel(document));
                        }

                        if (document.hasProperty("urlrewriter:rulefrom")) {
                            originalUrl = document.getProperty("urlrewriter:rulefrom").getString();
                        }

                        if (document.hasProperty("urlrewriter:ruleto")) {
                            rewriteUrl = document.getProperty("urlrewriter:ruleto").getString();
                        }

                        if (document.hasProperty("urlrewriter:ruletype")) {
                            rewriteType = document.getProperty("urlrewriter:ruletype").getString();
                        }
                    }
                }
            } catch (RepositoryException ex) {
                log.error("Unable to obtain state properties", ex);
            }
            loaded = true;
        }
    }

    public void setObservationContext(IObservationContext<? extends IObservable> context) {
        observable.setObservationContext(context);
    }

    public void startObservation() {
        observable.startObservation();
    }

    public void stopObservation() {
        observable.stopObservation();
    }


}
