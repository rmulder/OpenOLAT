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
package org.olat.course.assessment.ui.tool;

import java.util.Date;
import java.util.List;

import org.olat.commons.calendar.CalendarUtils;
import org.olat.core.gui.UserRequest;
import org.olat.core.gui.components.Component;
import org.olat.core.gui.components.link.Link;
import org.olat.core.gui.components.link.LinkFactory;
import org.olat.core.gui.components.stack.ButtonGroupComponent;
import org.olat.core.gui.components.stack.PopEvent;
import org.olat.core.gui.components.stack.TooledStackedPanel;
import org.olat.core.gui.components.stack.TooledStackedPanel.Align;
import org.olat.core.gui.components.tree.TreeNode;
import org.olat.core.gui.components.velocity.VelocityContainer;
import org.olat.core.gui.control.Controller;
import org.olat.core.gui.control.Event;
import org.olat.core.gui.control.WindowControl;
import org.olat.core.gui.control.controller.MainLayoutBasicController;
import org.olat.core.gui.control.generic.closablewrapper.CloseableModalController;
import org.olat.core.gui.control.generic.dtabs.Activateable2;
import org.olat.core.id.Identity;
import org.olat.core.id.OLATResourceable;
import org.olat.core.id.context.BusinessControlFactory;
import org.olat.core.id.context.ContextEntry;
import org.olat.core.id.context.StateEntry;
import org.olat.core.util.Formatter;
import org.olat.core.util.StringHelper;
import org.olat.core.util.Util;
import org.olat.core.util.resource.OresHelper;
import org.olat.course.CourseFactory;
import org.olat.course.ICourse;
import org.olat.course.assessment.AssessmentMode;
import org.olat.course.assessment.AssessmentModeCoordinationService;
import org.olat.course.assessment.AssessmentModeManager;
import org.olat.course.assessment.AssessmentModule;
import org.olat.course.assessment.CourseAssessmentService;
import org.olat.course.assessment.bulk.BulkAssessmentOverviewController;
import org.olat.course.assessment.ui.tool.event.AssessmentModeStatusEvent;
import org.olat.course.assessment.ui.tool.event.CourseNodeEvent;
import org.olat.course.config.ui.AssessmentResetController;
import org.olat.course.config.ui.AssessmentResetController.AssessmentResetEvent;
import org.olat.course.nodeaccess.NodeAccessService;
import org.olat.course.run.userview.UserCourseEnvironment;
import org.olat.modules.assessment.AssessmentService;
import org.olat.modules.assessment.ui.AssessedIdentityListState;
import org.olat.modules.assessment.ui.AssessmentToolContainer;
import org.olat.modules.assessment.ui.AssessmentToolSecurityCallback;
import org.olat.modules.assessment.ui.event.UserSelectionEvent;
import org.olat.modules.lecture.LectureService;
import org.olat.repository.RepositoryEntry;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 
 * Initial date: 21.07.2015<br>
 * @author srosse, stephane.rosse@frentix.com, http://www.frentix.com
 *
 */
public class AssessmentToolController extends MainLayoutBasicController implements Activateable2 {

	private final RepositoryEntry courseEntry;
	private final UserCourseEnvironment coachUserEnv;
	private final AssessmentToolSecurityCallback assessmentCallback;
	
	private Link usersLink;
	private Link groupsLink;
	private Link overviewLink;
	private Link bulkAssessmentLink;
	private Link recalculateLink;
	private Link stopAssessmentMode;
	private final TooledStackedPanel stackPanel;
	private final AssessmentToolContainer toolContainer;
	private final ButtonGroupComponent segmentButtonsCmp;

	private CloseableModalController cmc;
	private ConfirmStopAssessmentModeController stopCtrl;
	private AssessmentCourseTreeController courseTreeCtrl;
	private AssessmentCourseOverviewController overviewCtrl;
	private BulkAssessmentOverviewController bulkAssessmentOverviewCtrl;
	private AssessmentResetController assessmentResetCtrl;
	
	@Autowired
	private LectureService lectureService;
	@Autowired
	private CourseAssessmentService courseAssessmentService;
	@Autowired
	private NodeAccessService nodeAccessService;
	@Autowired
	private AssessmentService assessmentService;
	@Autowired
	private AssessmentModeManager assessmentModeManager;
	@Autowired
	private AssessmentModeCoordinationService assessmentModeCoordinationService;
	
	public AssessmentToolController(UserRequest ureq, WindowControl wControl, TooledStackedPanel stackPanel,
			RepositoryEntry courseEntry, UserCourseEnvironment coachUserEnv, AssessmentToolSecurityCallback assessmentCallback) {
		super(ureq, wControl);
		setTranslator(Util.createPackageTranslator(AssessmentModule.class, getLocale(), getTranslator()));
		this.courseEntry = courseEntry;
		this.stackPanel = stackPanel;
		this.coachUserEnv = coachUserEnv;
		this.assessmentCallback = assessmentCallback;
		
		toolContainer = new AssessmentToolContainer();

		stackPanel.addListener(this);
		segmentButtonsCmp = new ButtonGroupComponent("segments");
		
		overviewCtrl = new AssessmentCourseOverviewController(ureq, getWindowControl(), courseEntry, assessmentCallback);
		listenTo(overviewCtrl);
		putInitialPanel(overviewCtrl.getInitialComponent());
	}
	
	public void assessmentModeMessage() {
		List<AssessmentMode> modes = assessmentModeManager.getCurrentAssessmentMode(courseEntry, new Date());
		if(!modes.isEmpty()) {
			VelocityContainer warn = createVelocityContainer("assessment_mode_warn");
			if(modes.size() == 1) {
				AssessmentMode mode = modes.get(0);
				assessmemntModeMessageFormatting("assessment.mode.now",  modes, warn);
				if(canStopAssessmentMode(mode)) {
					String modeName = mode.getName();
					String label = translate("assessment.tool.stop", new String[] { StringHelper.escapeHtml(modeName) });
					stopAssessmentMode = LinkFactory.createCustomLink("assessment.stop", "stop", label, Link.BUTTON_SMALL | Link.NONTRANSLATED, warn, this);
					stopAssessmentMode.setIconLeftCSS("o_icon o_icon-fw o_as_mode_stop");
					if(assessmentModeCoordinationService.isDisadvantageCompensationExtensionTime(mode)) {
						stopAssessmentMode.setIconRightCSS("o_icon o_icon-fw o_icon_disadvantage_compensation");
					}
					stopAssessmentMode.setUserObject(mode);
				}
			} else {
				assessmemntModeMessageFormatting("assessment.mode.several.now",  modes, warn);
			}
			stackPanel.setMessageComponent(warn);
		} else {
			stackPanel.setMessageComponent(null);
		}
	}
	
	private void assessmemntModeMessageFormatting(String i18nMessage, List<AssessmentMode> modes, VelocityContainer warn) {
		Date begin = getBeginOfModes(modes);
		Date end = getEndOfModes(modes);
		
		String start;
		String stop;
		Formatter formatter = Formatter.getInstance(getLocale());
		if(CalendarUtils.isSameDay(begin, end)) {
			start = formatter.formatTimeShort(begin);
			stop = formatter.formatTimeShort(end);
		} else {
			start = formatter.formatDateAndTime(begin);
			stop = formatter.formatDateAndTime(end);
		}
		warn.contextPut("message", translate(i18nMessage, new String[] { start, stop }));
	}
	
	private Date getBeginOfModes(List<AssessmentMode> modes) {
		Date start = null;
		for(AssessmentMode mode:modes) {
			Date begin = mode.getBegin();
			if(start == null || (begin != null && begin.before(start))) {
				start = begin;
			}
		}
		return start;
	}
	
	private Date getEndOfModes(List<AssessmentMode> modes) {
		Date stop = null;
		for(AssessmentMode mode:modes) {
			Date end = mode.getEnd();
			if(stop == null || (end != null && end.after(stop))) {
				stop = end;
			}
		}
		return stop;
	}
	
	private boolean canStopAssessmentMode(AssessmentMode mode) {
		if(assessmentCallback.canStartStopAllAssessments()) {
			return assessmentModeCoordinationService.canStop(mode);
		} else if(mode.getLectureBlock() != null) {
			List<Identity> teachers = lectureService.getTeachers(mode.getLectureBlock());
			return teachers.contains(getIdentity())
					&& assessmentModeCoordinationService.canStop(mode);
		}
		return false;
	}
	
	public void initToolbar() {
		overviewLink = LinkFactory.createToolLink("overview", translate("overview"), this/*, "o_icon_user"*/);
		overviewLink.setElementCssClass("o_sel_assessment_tool_overview");
		segmentButtonsCmp.addButton(overviewLink, true);
		
		usersLink = LinkFactory.createToolLink("users", translate("users"), this/*, "o_icon_user"*/);
		usersLink.setElementCssClass("o_sel_assessment_tool_users");
		segmentButtonsCmp.addButton(usersLink, false);
		
		if(overviewCtrl.getNumOfBusinessGroups() > 0) {
			groupsLink = LinkFactory.createToolLink("groups", translate("groups"), this/*, "o_icon_group"*/);
			groupsLink.setElementCssClass("o_sel_assessment_tool_groups");
			segmentButtonsCmp.addButton(groupsLink, false);
		}
		stackPanel.addTool(segmentButtonsCmp, Align.segment, true);
		
		recalculateLink = LinkFactory.createToolLink("recalculate", translate("menu.recalculate"), this, "o_icon_recalculate");
		stackPanel.addTool(recalculateLink, Align.right);
		
		bulkAssessmentLink = LinkFactory.createToolLink("bulkAssessment", translate("menu.bulkfocus"), this, "o_icon_group");
		bulkAssessmentLink.setElementCssClass("o_sel_assessment_tool_bulk");
		stackPanel.addTool(bulkAssessmentLink, Align.right);
	}

	@Override
	protected void doDispose() {
		if(stackPanel != null) {
			stackPanel.removeListener(this);
		}
	}
	
	@Override
	public void activate(UserRequest ureq, List<ContextEntry> entries, StateEntry state) {
		if(entries == null || entries.isEmpty()) return;
		
		String resName = entries.get(0).getOLATResourceable().getResourceableTypeName();
		if("Users".equalsIgnoreCase(resName)) {
			List<ContextEntry> subEntries = entries.subList(1, entries.size());
			doSelectUsersView(ureq, null).activate(ureq, subEntries, entries.get(0).getTransientState());
		} else if("BusinessGroups".equalsIgnoreCase(resName) || "Groups".equalsIgnoreCase(resName)) {
			List<ContextEntry> subEntries = entries.subList(1, entries.size());
			doSelectGroupsView(ureq).activate(ureq, subEntries, entries.get(0).getTransientState());
		}
	}

	@Override
	protected void event(UserRequest ureq, Component source, Event event) {
		if (overviewLink == source) {
			cleanUp();
			stackPanel.popUpToController(this);
			addToHistory(ureq, getWindowControl());
		} else if (source == usersLink) {
			cleanUp();
			doSelectUsersView(ureq, null);
		} else if (groupsLink == source) {
			cleanUp();
			doSelectGroupsView(ureq);
		} else if(recalculateLink == source) {
			cleanUp();
			doOpenRecaluclate(ureq);
		} else if(bulkAssessmentLink == source) {
			cleanUp();
			doBulkAssessmentView(ureq);
		} else if(stopAssessmentMode == source) {
			doConfirmStop(ureq, (AssessmentMode)stopAssessmentMode.getUserObject());
		} else if(stackPanel == source) {
			if(event instanceof PopEvent) {
				PopEvent pe = (PopEvent)event;
				if(pe.isClose()) {
					stackPanel.popUpToRootController(ureq);
				} else if(pe.getController() == courseTreeCtrl) {
					removeAsListenerAndDispose(courseTreeCtrl);
					courseTreeCtrl = null;
					segmentButtonsCmp.setSelectedButton(overviewLink);
				}
			}
		}
	}

	@Override
	protected void event(UserRequest ureq, Controller source, Event event) {
		if(overviewCtrl == source) {
			if(event == AssessmentCourseOverviewController.SELECT_USERS_EVENT) {
				doSelectUsersView(ureq, null);
			} else if(event == AssessmentCourseOverviewController.SELECT_NODES_EVENT) {
				doSelectUsersView(ureq, null);
			} else if(event == AssessmentCourseOverviewController.SELECT_GROUPS_EVENT) {
				doSelectGroupsView(ureq);
			} else if(event == AssessmentCourseOverviewController.SELECT_PASSED_EVENT) {
				doSelectUsersView(ureq, new AssessedIdentityListState("passed"));
			} else if(event == AssessmentCourseOverviewController.SELECT_FAILED_EVENT) {
				doSelectUsersView(ureq, new AssessedIdentityListState("failed"));
			} else if (event instanceof UserSelectionEvent) {
				UserSelectionEvent use = (UserSelectionEvent)event;
				if(use.getCourseNodeIdents() == null || use.getCourseNodeIdents().isEmpty() || use.getCourseNodeIdents().size() > 1) {
					OLATResourceable resource = OresHelper.createOLATResourceableInstance("Identity", use.getIdentityKey());
					List<ContextEntry> entries = BusinessControlFactory.getInstance()
							.createCEListFromResourceable(resource, new AssessedIdentityListState("inReview"));
					doSelectUsersView(ureq, null).activate(ureq, entries, null);
				} else {
					OLATResourceable nodeRes = OresHelper.createOLATResourceableInstance("Node", Long.valueOf(use.getCourseNodeIdents().get(0)));
					OLATResourceable idRes = OresHelper.createOLATResourceableInstance("Identity", use.getIdentityKey());
					List<ContextEntry> entries = BusinessControlFactory.getInstance().createCEListFromString(nodeRes, idRes);
					doSelectUsersView(ureq, null).activate(ureq, entries, null);
				}
			} else if(event instanceof CourseNodeEvent) {
				CourseNodeEvent cne = (CourseNodeEvent)event;
				if(cne.getIdent() != null) {
					doSelectNodeView(ureq, cne.getIdent());
				}
			} else if(event instanceof AssessmentModeStatusEvent) {
				assessmentModeMessage();
			}
		} else if(stopCtrl == source) {
			if(event == Event.DONE_EVENT) {
				doAfterStop();
			}
			cmc.deactivate();
			cleanUp();
		} else if(source == assessmentResetCtrl) {
			if (event instanceof AssessmentResetEvent) {
				AssessmentResetEvent are = (AssessmentResetEvent)event;
				doRecalculate(are);
			}
			cmc.deactivate();
			cleanUp();
		} else if (source == cmc) {
			cmc.deactivate();
			cleanUp();
		}
		super.event(ureq, source, event);
	}
	
	private void cleanUp() {
		removeAsListenerAndDispose(bulkAssessmentOverviewCtrl);
		removeAsListenerAndDispose(assessmentResetCtrl);
		removeAsListenerAndDispose(stopCtrl);
		removeAsListenerAndDispose(cmc);
		bulkAssessmentOverviewCtrl = null;
		assessmentResetCtrl = null;
		stopCtrl = null;
		cmc = null;
	}
	
	private void doBulkAssessmentView(UserRequest ureq) {
		bulkAssessmentOverviewCtrl = new BulkAssessmentOverviewController(ureq, getWindowControl(), courseEntry);
		listenTo(bulkAssessmentOverviewCtrl);
		stackPanel.pushController(translate("menu.bulkfocus"), bulkAssessmentOverviewCtrl);
	}
	
	private void doOpenRecaluclate(UserRequest ureq) {
		boolean showResetOverriden = !nodeAccessService
				.isScoreCalculatorSupported(coachUserEnv.getCourseEnvironment().getCourseConfig().getNodeAccessType());
		assessmentResetCtrl = new AssessmentResetController(ureq, getWindowControl(), showResetOverriden, false);
		listenTo(assessmentResetCtrl);
		cmc = new CloseableModalController(getWindowControl(), translate("close"),
				assessmentResetCtrl.getInitialComponent(), true, translate("assessment.reset.title"), true);
		listenTo(cmc);
		cmc.activate();
	}
	
	private void doRecalculate(AssessmentResetEvent are) {
		if (are.isResetOverriden()) {
			assessmentService.resetAllOverridenRootPassed(courseEntry);
		}
		if (are.isResetPassed()) {
			assessmentService.resetAllRootPassed(courseEntry);
		}
		if (are.isRecalculateAll()) {
			ICourse course = CourseFactory.loadCourse(courseEntry);
			courseAssessmentService.evaluateAll(course);
		}
	}
	
	private AssessmentCourseTreeController doSelectGroupsView(UserRequest ureq) {
		if(courseTreeCtrl == null || courseTreeCtrl.isDisposed()) {
			stackPanel.popUpToController(this);
			
			courseTreeCtrl = new AssessmentCourseTreeController(ureq, getWindowControl(), stackPanel, courseEntry, coachUserEnv, toolContainer, assessmentCallback);
			listenTo(courseTreeCtrl);
			TreeNode node = courseTreeCtrl.getSelectedCourseNode();
			stackPanel.pushController(node.getTitle(), "o_icon ".concat(node.getIconCssClass()), courseTreeCtrl);
		}
		courseTreeCtrl.switchToBusinessGroupsView(ureq);
		segmentButtonsCmp.setSelectedButton(groupsLink);
		return courseTreeCtrl;
	}

	private AssessmentCourseTreeController doSelectUsersView(UserRequest ureq, StateEntry stateUserList) {
		if(courseTreeCtrl == null || courseTreeCtrl.isDisposed()) {
			stackPanel.popUpToController(this);
			
			courseTreeCtrl = new AssessmentCourseTreeController(ureq, getWindowControl(), stackPanel, courseEntry, coachUserEnv, toolContainer, assessmentCallback);
			listenTo(courseTreeCtrl);
			TreeNode node = courseTreeCtrl.getSelectedCourseNode();
			stackPanel.pushController(node.getTitle(), "o_icon " + node.getIconCssClass(), courseTreeCtrl);
		}
		courseTreeCtrl.switchToUsersView(ureq, stateUserList);
		segmentButtonsCmp.setSelectedButton(usersLink);
		return courseTreeCtrl;
	}
	
	private AssessmentCourseTreeController doSelectNodeView(UserRequest ureq, String nodeIdent) {
		if(courseTreeCtrl == null || courseTreeCtrl.isDisposed()) {
			stackPanel.popUpToController(this);
			
			courseTreeCtrl = new AssessmentCourseTreeController(ureq, getWindowControl(), stackPanel, courseEntry, coachUserEnv, toolContainer, assessmentCallback);
			listenTo(courseTreeCtrl);
			OLATResourceable nodeRes = OresHelper.createOLATResourceableInstance("Node", Long.valueOf(nodeIdent));
			List<ContextEntry> entries = BusinessControlFactory.getInstance().createCEListFromString(nodeRes);
			courseTreeCtrl.activate(ureq, entries, null);
			
			TreeNode node = courseTreeCtrl.getSelectedCourseNode();
			stackPanel.pushController(node.getTitle(), "o_icon " + node.getIconCssClass(), courseTreeCtrl);
		}
		courseTreeCtrl.switchToUsersView(ureq, null);
		segmentButtonsCmp.setSelectedButton(usersLink);
		return courseTreeCtrl;
	}
	
	private void doConfirmStop(UserRequest ureq, AssessmentMode mode) {
		if(guardModalController(stopCtrl)) return;
		
		stopCtrl = new ConfirmStopAssessmentModeController(ureq, getWindowControl(), mode);
		listenTo(stopCtrl);

		String title = translate("confirm.stop.title");
		cmc = new CloseableModalController(getWindowControl(), "close", stopCtrl.getInitialComponent(), true, title, true);
		cmc.activate();
		listenTo(cmc);
	}
	
	private void doAfterStop() {
		assessmentModeMessage();
		overviewCtrl.reloadAssessmentModes();
	}
}
