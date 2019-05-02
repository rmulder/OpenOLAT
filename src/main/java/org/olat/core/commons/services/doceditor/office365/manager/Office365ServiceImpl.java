/**
 * <a href="http://www.openolat.org">
 * OpenOLAT - Online Learning and Training</a><br>
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); <br>
 * you may not use this file except in compliance with the License.<br>
 * You may obtain a copy of the License at the
 * <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache homepage</a>
 * <p>
 * Unless required by applicable law or agreed to in writing,<br>
 * software distributed under the License is distributed on an "AS IS" BASIS, <br>
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. <br>
 * See the License for the specific language governing permissions and <br>
 * limitations under the License.
 * <p>
 * Initial code contributed and copyrighted by<br>
 * frentix GmbH, http://www.frentix.com
 * <p>
 */
package org.olat.core.commons.services.doceditor.office365.manager;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import javax.annotation.PostConstruct;

import org.olat.core.commons.services.doceditor.DocEditor.Mode;
import org.olat.core.commons.services.doceditor.DocEditorSecurityCallback;
import org.olat.core.commons.services.doceditor.office365.Office365Module;
import org.olat.core.commons.services.doceditor.office365.Office365RefreshDiscoveryEvent;
import org.olat.core.commons.services.doceditor.office365.Office365Service;
import org.olat.core.commons.services.doceditor.wopi.Access;
import org.olat.core.commons.services.doceditor.wopi.Action;
import org.olat.core.commons.services.doceditor.wopi.Discovery;
import org.olat.core.commons.services.doceditor.wopi.WopiService;
import org.olat.core.commons.services.vfs.VFSMetadata;
import org.olat.core.commons.services.vfs.VFSRepositoryService;
import org.olat.core.gui.control.Event;
import org.olat.core.helpers.Settings;
import org.olat.core.id.Identity;
import org.olat.core.logging.OLog;
import org.olat.core.logging.Tracing;
import org.olat.core.util.FileUtils;
import org.olat.core.util.StringHelper;
import org.olat.core.util.coordinate.CoordinatorManager;
import org.olat.core.util.event.GenericEventListener;
import org.olat.core.util.vfs.VFSConstants;
import org.olat.core.util.vfs.VFSLeaf;
import org.olat.core.util.vfs.VFSLockApplicationType;
import org.olat.core.util.vfs.VFSLockManager;
import org.olat.core.util.vfs.VFSManager;
import org.olat.core.util.vfs.lock.LockInfo;
import org.olat.restapi.security.RestSecurityHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 
 * Initial date: 26.04.2019<br>
 * @author uhensler, urs.hensler@frentix.com, http://www.frentix.com
 *
 */
@Service
public class Office365ServiceImpl implements Office365Service, GenericEventListener {

	private static final OLog log = Tracing.createLoggerFor(Office365ServiceImpl.class);

	private static final String LOCK_APP = "office365";
	
	private Discovery discovery;
	private Collection<String> cspUrls;
	
	@Autowired
	private Office365Module office365Module;
	@Autowired
	private WopiService wopiService;
	@Autowired
	private UrlParser urlParser;
	@Autowired
	private VFSRepositoryService vfsRepositoryService;
	@Autowired
	private VFSLockManager lockManager;
	
	@PostConstruct
	private void init() {
		CoordinatorManager.getInstance().getCoordinator().getEventBus().registerFor(this, null, REFRESH_EVENT_ORES);
	}

	@Override
	public VFSLeaf getVfsLeaf(Access access) {
		return wopiService.getVfsLeaf(access);
	}

	@Override
	public Access createAccess(VFSMetadata vfsMetadata, Identity identity, DocEditorSecurityCallback secCallback) {
		Date expiresIn24Hours = Date.from(Instant.now().plus(Duration.ofHours(24)));
		return wopiService.getOrCreateAccess(vfsMetadata, identity, secCallback, expiresIn24Hours);
	}

	@Override
	public Access getAccess(String accessToken) {
		return wopiService.getAccess(accessToken);
	}

	@Override
	public void deleteAccess(Access access) {
		if (access == null) return;
		
		wopiService.deleteAccess(access.getToken());
	}

	@Override
	public boolean updateContent(Access access, InputStream fileInputStream) {
		VFSLeaf vfsLeaf = wopiService.getVfsLeaf(access);
		boolean updated = false;
		try {
			if(access.isVersionControlled() && vfsLeaf.canVersion() == VFSConstants.YES) {
				updated = vfsRepositoryService.addVersion(vfsLeaf, access.getIdentity(), "Office 365",
						fileInputStream);
			} else {
				updated = VFSManager.copyContent(fileInputStream, vfsLeaf);
			}
		} catch(Exception e) {
			log.error("", e);
		}
		if (updated) {
			refreshLock(vfsLeaf);
		}
		return updated;
	}

	private void refreshLock(VFSLeaf vfsLeaf) {
		LockInfo lock = lockManager.getLock(vfsLeaf);
		if (lock != null) {
			long inADay = System.currentTimeMillis() + (24 * 60 * 60 * 1000);
			lock.setExpiresAt(inADay);
		}
	}
	
	@Override
	public void event(Event event) {
		if (event instanceof Office365RefreshDiscoveryEvent) {
			deleteDiscovery();
		}
	}

	private Discovery getDiscovery() {
		if (discovery == null) {
			String discoveryUrl = getDiscoveryUrl();
			discovery = wopiService.getDiscovery(discoveryUrl);
			log.info("Recieved new WOPI discovery from " + discoveryUrl);
		}
		return discovery;
	}

	private String getDiscoveryUrl() {
		return office365Module.getBaseUrl() + wopiService.getRegularDiscoveryPath();
	}

	private void deleteDiscovery() {
		discovery = null;
		cspUrls = null;
		log.info("Deleted WOPI discovery. It will be refreshed with the next access.");
	}

	@Override
	public Collection<String> getContentSecurityPolicyUrls() {
		if (cspUrls == null) {
			Collection<Action> actions = wopiService.getActions(getDiscovery());
			Set<String> urls = new HashSet<>();
			for (Action action : actions) {
				String protocolAndDomain = urlParser.getProtocolAndDomain(action.getUrlSrc());
				urls.add(protocolAndDomain);
			}
			cspUrls = urls;
		}
		return cspUrls;
	}

	@Override
	public String getEditorActionUrl(VFSMetadata vfsMetadata) {
		StringBuilder wopiPath = new StringBuilder();
		wopiPath.append(Settings.getServerContextPathURI());
		wopiPath.append(RestSecurityHelper.SUB_CONTEXT);
		wopiPath.append("/office365/wopi/files/");
		wopiPath.append(vfsMetadata.getUuid());

		StringBuilder urlSb = new StringBuilder();
		urlSb.append(getEditorBaseUrl(vfsMetadata));
		urlSb.append("WOPISrc=");
		urlSb.append(StringHelper.urlEncodeUTF8(wopiPath.toString()));
		String url = urlSb.toString();
		log.debug("Editor action URL: " + url);
		return url;
	}

	private String getEditorBaseUrl(VFSMetadata vfsMetadata) {
		String suffix = FileUtils.getFileSuffix(vfsMetadata.getFilename());
		Action action = wopiService.getAction(getDiscovery(), "edit", suffix);
		if (action == null) {
			action = wopiService.getAction(getDiscovery(), "view", suffix);
		}

		String url = action != null? action.getUrlSrc(): null;
		// replace all url query parameters
		url = url!= null? url.substring(0, url.indexOf("?") + 1): null;
		return url;
	}

	@Override
	public boolean isSupportingFormat(String suffix, Mode mode) {
		boolean accepts = wopiService.hasAction(getDiscovery(), "edit", suffix);
		if (!accepts && Mode.VIEW.equals(mode)) {
			accepts = wopiService.hasAction(getDiscovery(), "view", suffix);
		}
		return accepts;
	}

	@Override
	public boolean isLockNeeded(Mode mode) {
		return Mode.EDIT.equals(mode);
	}

	@Override
	public boolean isLockedForMe(VFSLeaf vfsLeaf, Identity identity) {
		return lockManager.isLockedForMe(vfsLeaf, identity, VFSLockApplicationType.collaboration, LOCK_APP);
	}

	@Override
	public String getLockToken(VFSLeaf vfsLeaf) {
		LockInfo lock = lockManager.getLock(vfsLeaf);
		if (lock != null && lock.getTokensSize() >= 1) {
			return lock.getTokens().get(0);
		}
		return null;
	}

	@Override
	public void lock(VFSLeaf vfsLeaf, Identity identity, String lockToken) {
		LockInfo lock = lockManager.getLock(vfsLeaf);
		if (lock == null) {
			lockManager.lock(vfsLeaf, identity, VFSLockApplicationType.collaboration, LOCK_APP);
			lock = lockManager.getLock(vfsLeaf);
			lock.getTokens().clear(); // the generated, internal token for the identity
			
			refreshLock(lock);
		}
		lock.getTokens().add(lockToken);
	}

	@Override
	public boolean canUnlock(VFSLeaf vfsLeaf, String lockToken) {
		LockInfo lock = lockManager.getLock(vfsLeaf);
		if (lock != null && lock.getTokens() != null) {
			for (String token : lock.getTokens()) {
				if (token.equals(lockToken)) {
					return true;
				}
			}
		}
		return false;
	}

	@Override
	public void unlock(VFSLeaf vfsLeaf, String lockToken) {
		LockInfo lock = lockManager.getLock(vfsLeaf);
		if (lock != null && lock.getTokens() != null) {
			lock.getTokens().removeIf(token -> token.equals(lockToken));
			if (lock.getTokens().isEmpty()) {
				lockManager.unlock(vfsLeaf, VFSLockApplicationType.collaboration);
			}
		}
	}

	@Override
	public void refreshLock(VFSLeaf vfsLeaf, String lockToken) {
		LockInfo lock = lockManager.getLock(vfsLeaf);
		if (lock != null && lock.getTokens() != null) {
			for (String token : lock.getTokens()) {
				if (token.equals(lockToken)) {
					refreshLock(lock);
				}
			}
		}
	}

	private void refreshLock(LockInfo lock) {
		// https://wopi.readthedocs.io/projects/wopirest/en/latest/files/RefreshLock.html
		Calendar now = Calendar.getInstance();
		now.add(Calendar.MINUTE, 30);
		Date in30Mins = now.getTime();
		lock.setExpiresAt(in30Mins.getTime());
	}

}