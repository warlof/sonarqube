/*
 * SonarQube
 * Copyright (C) 2009-2017 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.issue.ws;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.sonar.api.config.internal.MapSettings;
import org.sonar.api.issue.Issue;
import org.sonar.api.resources.Languages;
import org.sonar.api.rule.RuleStatus;
import org.sonar.api.server.ws.WebService;
import org.sonar.api.utils.DateUtils;
import org.sonar.api.utils.Durations;
import org.sonar.api.utils.System2;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.DbTester;
import org.sonar.db.component.ComponentDto;
import org.sonar.db.component.ComponentTesting;
import org.sonar.db.issue.IssueChangeDto;
import org.sonar.db.issue.IssueDto;
import org.sonar.db.issue.IssueTesting;
import org.sonar.db.organization.OrganizationDao;
import org.sonar.db.organization.OrganizationDto;
import org.sonar.db.organization.OrganizationTesting;
import org.sonar.db.permission.GroupPermissionDto;
import org.sonar.db.rule.RuleDto;
import org.sonar.db.rule.RuleTesting;
import org.sonar.db.user.UserDto;
import org.sonar.server.es.EsTester;
import org.sonar.server.es.SearchOptions;
import org.sonar.server.es.StartupIndexer;
import org.sonar.server.issue.ActionFinder;
import org.sonar.server.issue.IssueFieldsSetter;
import org.sonar.server.issue.IssueQuery;
import org.sonar.server.issue.IssueQueryFactory;
import org.sonar.server.issue.TransitionService;
import org.sonar.server.issue.index.IssueIndex;
import org.sonar.server.issue.index.IssueIndexDefinition;
import org.sonar.server.issue.index.IssueIndexer;
import org.sonar.server.issue.index.IssueIteratorFactory;
import org.sonar.server.issue.workflow.FunctionExecutor;
import org.sonar.server.issue.workflow.IssueWorkflow;
import org.sonar.server.permission.index.AuthorizationTypeSupport;
import org.sonar.server.permission.index.PermissionIndexer;
import org.sonar.server.tester.UserSessionRule;
import org.sonar.server.ws.TestResponse;
import org.sonar.server.ws.WsActionTester;
import org.sonar.server.ws.WsResponseCommonFormat;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.sonar.api.web.UserRole.ISSUE_ADMIN;
import static org.sonarqube.ws.client.issue.IssuesWsParameters.ACTION_SEARCH;
import static org.sonarqube.ws.client.issue.IssuesWsParameters.CONTROLLER_ISSUES;
import static org.sonarqube.ws.client.issue.IssuesWsParameters.DEPRECATED_FACET_MODE_DEBT;
import static org.sonarqube.ws.client.issue.IssuesWsParameters.FACET_MODE_EFFORT;
import static org.sonarqube.ws.client.issue.IssuesWsParameters.PARAM_ADDITIONAL_FIELDS;
import static org.sonarqube.ws.client.issue.IssuesWsParameters.PARAM_COMPONENTS;
import static org.sonarqube.ws.client.issue.IssuesWsParameters.PARAM_CREATED_AFTER;
import static org.sonarqube.ws.client.issue.IssuesWsParameters.PARAM_HIDE_COMMENTS;
import static org.sonarqube.ws.client.issue.IssuesWsParameters.PARAM_PAGE_INDEX;
import static org.sonarqube.ws.client.issue.IssuesWsParameters.PARAM_PAGE_SIZE;

@Ignore
public class SearchActionMediumTest {

  @Rule
  public UserSessionRule userSessionRule = UserSessionRule.standalone();
  @Rule
  public DbTester dbTester = DbTester.create();
  @Rule
  public EsTester esTester = new EsTester(new IssueIndexDefinition(new MapSettings().asConfig()));
  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  private DbClient db = dbTester.getDbClient();
  private DbSession session = dbTester.getSession();
  private IssueIndex issueIndex = new IssueIndex(esTester.client(), System2.INSTANCE, userSessionRule, new AuthorizationTypeSupport(userSessionRule));
  private IssueIndexer issueIndexer = new IssueIndexer(esTester.client(), db, new IssueIteratorFactory(db));
  private IssueQueryFactory issueQueryFactory = new IssueQueryFactory(db, System2.INSTANCE, userSessionRule);
  private IssueFieldsSetter issueFieldsSetter = new IssueFieldsSetter();
  private IssueWorkflow issueWorkflow = new IssueWorkflow(new FunctionExecutor(issueFieldsSetter), issueFieldsSetter);
  private SearchResponseLoader searchResponseLoader = new SearchResponseLoader(userSessionRule, db, new ActionFinder(userSessionRule), new TransitionService(userSessionRule, issueWorkflow));
  private Languages languages = new Languages();
  private SearchResponseFormat searchResponseFormat = new SearchResponseFormat(new Durations(), new WsResponseCommonFormat(languages), languages, new AvatarResolverImpl());
  private WsActionTester wsTester = new WsActionTester(new SearchAction(userSessionRule, issueIndex, issueQueryFactory, searchResponseLoader, searchResponseFormat));
  private OrganizationDto defaultOrganization;
  private OrganizationDto otherOrganization1;
  private OrganizationDto otherOrganization2;
  private StartupIndexer permissionIndexer = new PermissionIndexer(db, esTester.client(), issueIndexer);

  @Before
  public void setUp() {
    session = db.openSession(false);
    OrganizationDao organizationDao = db.organizationDao();
    this.defaultOrganization = dbTester.getDefaultOrganization();
    this.otherOrganization1 = OrganizationTesting.newOrganizationDto().setKey("my-org-1");
    this.otherOrganization2 = OrganizationTesting.newOrganizationDto().setKey("my-org-2");
    organizationDao.insert(session, this.otherOrganization1, false);
    organizationDao.insert(session, this.otherOrganization2, false);
    session.commit();
  }

  @Test
  public void empty_search() throws Exception {
    TestResponse result = wsTester.newGetRequest(CONTROLLER_ISSUES, ACTION_SEARCH)
      .execute();

    assertThat(result).isNotNull();
    result.assertJson(this.getClass(), "empty_result.json");
  }

  @Test
  public void response_contains_all_fields_except_additional_fields() throws Exception {
    db.userDao().insert(session, new UserDto().setLogin("simon").setName("Simon").setEmail("simon@email.com"));
    db.userDao().insert(session, new UserDto().setLogin("fabrice").setName("Fabrice").setEmail("fabrice@email.com"));

    ComponentDto project = insertComponent(ComponentTesting.newPublicProjectDto(otherOrganization2, "PROJECT_ID").setDbKey("PROJECT_KEY"));
    indexPermissions();
    ComponentDto file = insertComponent(ComponentTesting.newFileDto(project, null, "FILE_ID").setDbKey("FILE_KEY"));
    IssueDto issue = IssueTesting.newDto(newRule(), file, project)
      .setKee("82fd47d4-b650-4037-80bc-7b112bd4eac2")
      .setEffort(10L)
      .setMessage("the message")
      .setStatus(Issue.STATUS_RESOLVED)
      .setResolution(Issue.RESOLUTION_FIXED)
      .setSeverity("MAJOR")
      .setAuthorLogin("John")
      .setAssignee("simon")
      .setTags(asList("bug", "owasp"))
      .setIssueCreationDate(DateUtils.parseDateTime("2014-09-04T00:00:00+0100"))
      .setIssueUpdateDate(DateUtils.parseDateTime("2017-12-04T00:00:00+0100"));
    db.issueDao().insert(session, issue);
    session.commit();
    issueIndexer.indexOnStartup(issueIndexer.getIndexTypes());

    wsTester.newGetRequest(CONTROLLER_ISSUES, ACTION_SEARCH).execute()
      .assertJson(this.getClass(), "response_contains_all_fields_except_additional_fields.json");
  }

  @Test
  public void issue_with_comments() throws Exception {
    db.userDao().insert(session, new UserDto().setLogin("john").setName("John"));
    db.userDao().insert(session, new UserDto().setLogin("fabrice").setName("Fabrice").setEmail("fabrice@email.com"));

    ComponentDto project = insertComponent(ComponentTesting.newPublicProjectDto(otherOrganization2, "PROJECT_ID").setDbKey("PROJECT_KEY"));
    indexPermissions();
    ComponentDto file = insertComponent(ComponentTesting.newFileDto(project, null, "FILE_ID").setDbKey("FILE_KEY"));
    IssueDto issue = IssueTesting.newDto(newRule(), file, project)
      .setKee("82fd47d4-b650-4037-80bc-7b112bd4eac2");
    db.issueDao().insert(session, issue);

    db.issueChangeDao().insert(session,
      new IssueChangeDto().setIssueKey(issue.getKey())
        .setKey("COMMENT-ABCD")
        .setChangeData("*My comment*")
        .setChangeType(IssueChangeDto.TYPE_COMMENT)
        .setUserLogin("john")
        .setCreatedAt(DateUtils.parseDateTime("2014-09-09T12:00:00+0000").getTime()));
    db.issueChangeDao().insert(session,
      new IssueChangeDto().setIssueKey(issue.getKey())
        .setKey("COMMENT-ABCE")
        .setChangeData("Another comment")
        .setChangeType(IssueChangeDto.TYPE_COMMENT)
        .setUserLogin("fabrice")
        .setCreatedAt(DateUtils.parseDateTime("2014-09-10T12:00:00+0000").getTime()));
    session.commit();
    indexIssues();

    userSessionRule.logIn("john");
    wsTester.newGetRequest(CONTROLLER_ISSUES, ACTION_SEARCH)
      .setParam("additionalFields", "comments,users")
      .execute()
      .assertJson(this.getClass(), "issue_with_comments.json");
  }

  @Test
  public void issue_with_comment_hidden() throws Exception {
    db.userDao().insert(session, new UserDto().setLogin("john").setName("John").setEmail("john@email.com"));
    db.userDao().insert(session, new UserDto().setLogin("fabrice").setName("Fabrice").setEmail("fabrice@email.com"));

    ComponentDto project = insertComponent(ComponentTesting.newPublicProjectDto(otherOrganization1, "PROJECT_ID").setDbKey("PROJECT_KEY"));
    indexPermissions();
    ComponentDto file = insertComponent(ComponentTesting.newFileDto(project, null, "FILE_ID").setDbKey("FILE_KEY"));
    IssueDto issue = IssueTesting.newDto(newRule(), file, project)
      .setKee("82fd47d4-b650-4037-80bc-7b112bd4eac2");
    db.issueDao().insert(session, issue);

    db.issueChangeDao().insert(session,
      new IssueChangeDto().setIssueKey(issue.getKey())
        .setKey("COMMENT-ABCD")
        .setChangeData("*My comment*")
        .setChangeType(IssueChangeDto.TYPE_COMMENT)
        .setUserLogin("john")
        .setCreatedAt(DateUtils.parseDateTime("2014-09-09T12:00:00+0000").getTime()));
    db.issueChangeDao().insert(session,
      new IssueChangeDto().setIssueKey(issue.getKey())
        .setKey("COMMENT-ABCE")
        .setChangeData("Another comment")
        .setChangeType(IssueChangeDto.TYPE_COMMENT)
        .setUserLogin("fabrice")
        .setCreatedAt(DateUtils.parseDateTime("2014-09-10T19:10:03+0000").getTime()));
    session.commit();
    indexIssues();

    userSessionRule.logIn("john");
    TestResponse result = wsTester.newGetRequest(CONTROLLER_ISSUES, ACTION_SEARCH).setParam(PARAM_HIDE_COMMENTS, "true").execute();
    result.assertJson(this.getClass(), "issue_with_comment_hidden.json");
    assertThat(result.getInput()).doesNotContain("fabrice");
  }

  @Test
  public void load_additional_fields() throws Exception {
    db.userDao().insert(session, new UserDto().setLogin("simon").setName("Simon").setEmail("simon@email.com"));
    db.userDao().insert(session, new UserDto().setLogin("fabrice").setName("Fabrice").setEmail("fabrice@email.com"));
    ComponentDto project = insertComponent(ComponentTesting.newPublicProjectDto(otherOrganization2, "PROJECT_ID").setDbKey("PROJECT_KEY").setLanguage("java"));
    indexPermissions();
    ComponentDto file = insertComponent(ComponentTesting.newFileDto(project, null, "FILE_ID").setDbKey("FILE_KEY").setLanguage("js"));

    IssueDto issue = IssueTesting.newDto(newRule(), file, project)
      .setKee("82fd47d4-b650-4037-80bc-7b112bd4eac2")
      .setAuthorLogin("John")
      .setAssignee("simon");
    db.issueDao().insert(session, issue);
    session.commit();
    indexIssues();

    userSessionRule.logIn("john");
    wsTester.newGetRequest(CONTROLLER_ISSUES, ACTION_SEARCH)
      .setParam("additionalFields", "_all").execute()
      .assertJson(this.getClass(), "load_additional_fields.json");
  }

  @Test
  public void load_additional_fields_with_issue_admin_permission() throws Exception {
    db.userDao().insert(session, new UserDto().setLogin("simon").setName("Simon").setEmail("simon@email.com"));
    db.userDao().insert(session, new UserDto().setLogin("fabrice").setName("Fabrice").setEmail("fabrice@email.com"));
    ComponentDto project = insertComponent(ComponentTesting.newPublicProjectDto(otherOrganization1, "PROJECT_ID").setDbKey("PROJECT_KEY").setLanguage("java"));
    grantPermissionToAnyone(project, ISSUE_ADMIN);
    indexPermissions();
    ComponentDto file = insertComponent(ComponentTesting.newFileDto(project, null, "FILE_ID").setDbKey("FILE_KEY").setLanguage("js"));

    IssueDto issue = IssueTesting.newDto(newRule(), file, project)
      .setKee("82fd47d4-b650-4037-80bc-7b112bd4eac2")
      .setAuthorLogin("John")
      .setAssignee("simon");
    db.issueDao().insert(session, issue);
    session.commit();
    indexIssues();

    userSessionRule.logIn("john")
      .addProjectPermission(ISSUE_ADMIN, project); // granted by Anyone
    wsTester.newGetRequest(CONTROLLER_ISSUES, ACTION_SEARCH)
      .setParam("additionalFields", "_all").execute()
      .assertJson(this.getClass(), "load_additional_fields_with_issue_admin_permission.json");
  }

  @Test
  public void issue_on_removed_file() throws Exception {
    RuleDto rule = newRule();
    ComponentDto project = insertComponent(ComponentTesting.newPublicProjectDto(otherOrganization2, "PROJECT_ID").setDbKey("PROJECT_KEY"));
    indexPermissions();
    ComponentDto removedFile = insertComponent(ComponentTesting.newFileDto(project, null).setUuid("REMOVED_FILE_ID")
      .setDbKey("REMOVED_FILE_KEY")
      .setEnabled(false));

    IssueDto issue = IssueTesting.newDto(rule, removedFile, project)
      .setKee("82fd47d4-b650-4037-80bc-7b112bd4eac2")
      .setComponent(removedFile)
      .setStatus("OPEN").setResolution("OPEN")
      .setSeverity("MAJOR")
      .setIssueCreationDate(DateUtils.parseDateTime("2014-09-04T00:00:00+0100"))
      .setIssueUpdateDate(DateUtils.parseDateTime("2017-12-04T00:00:00+0100"));
    db.issueDao().insert(session, issue);
    session.commit();
    indexIssues();

    wsTester.newGetRequest(CONTROLLER_ISSUES, ACTION_SEARCH)
      .execute()
      .assertJson(this.getClass(), "issue_on_removed_file.json");
  }

  @Test
  public void apply_paging_with_one_component() throws Exception {
    RuleDto rule = newRule();
    ComponentDto project = insertComponent(ComponentTesting.newPublicProjectDto(otherOrganization2, "PROJECT_ID").setDbKey("PROJECT_KEY"));
    indexPermissions();
    ComponentDto file = insertComponent(ComponentTesting.newFileDto(project, null, "FILE_ID").setDbKey("FILE_KEY"));
    for (int i = 0; i < SearchOptions.MAX_LIMIT + 1; i++) {
      IssueDto issue = IssueTesting.newDto(rule, file, project);
      db.issueDao().insert(session, issue);
    }
    session.commit();
    indexIssues();

    wsTester.newGetRequest(CONTROLLER_ISSUES, ACTION_SEARCH).setParam(PARAM_COMPONENTS, file.getDbKey()).execute()
      .assertJson(this.getClass(), "apply_paging_with_one_component.json");
  }

  @Test
  public void components_contains_sub_projects() throws Exception {
    ComponentDto project = insertComponent(ComponentTesting.newPublicProjectDto(otherOrganization1, "PROJECT_ID").setDbKey("ProjectHavingModule"));
    indexPermissions();
    ComponentDto module = insertComponent(ComponentTesting.newModuleDto(project).setDbKey("ModuleHavingFile"));
    ComponentDto file = insertComponent(ComponentTesting.newFileDto(module, null, "BCDE").setDbKey("FileLinkedToModule"));
    IssueDto issue = IssueTesting.newDto(newRule(), file, project);
    db.issueDao().insert(session, issue);
    session.commit();
    indexIssues();

    wsTester.newGetRequest(CONTROLLER_ISSUES, ACTION_SEARCH).setParam(PARAM_ADDITIONAL_FIELDS, "_all").execute()
      .assertJson(this.getClass(), "components_contains_sub_projects.json");
  }

  @Test
  public void display_facets() throws Exception {
    ComponentDto project = insertComponent(ComponentTesting.newPublicProjectDto(otherOrganization1, "PROJECT_ID").setDbKey("PROJECT_KEY"));
    indexPermissions();
    ComponentDto file = insertComponent(ComponentTesting.newFileDto(project, null, "FILE_ID").setDbKey("FILE_KEY"));
    IssueDto issue = IssueTesting.newDto(newRule(), file, project)
      .setIssueCreationDate(DateUtils.parseDate("2014-09-04"))
      .setIssueUpdateDate(DateUtils.parseDate("2017-12-04"))
      .setEffort(10L)
      .setStatus("OPEN")
      .setKee("82fd47d4-b650-4037-80bc-7b112bd4eac2")
      .setSeverity("MAJOR");
    db.issueDao().insert(session, issue);
    session.commit();
    indexIssues();

    userSessionRule.logIn("john");
    wsTester.newGetRequest(CONTROLLER_ISSUES, ACTION_SEARCH)
      .setParam("resolved", "false")
      .setParam(WebService.Param.FACETS, "statuses,severities,resolutions,projectUuids,rules,fileUuids,assignees,languages,actionPlans,types")
      .execute()
      .assertJson(this.getClass(), "display_facets.json");
  }

  @Test
  public void display_facets_in_effort_mode() throws Exception {
    ComponentDto project = insertComponent(ComponentTesting.newPublicProjectDto(otherOrganization2, "PROJECT_ID").setDbKey("PROJECT_KEY"));
    indexPermissions();
    ComponentDto file = insertComponent(ComponentTesting.newFileDto(project, null, "FILE_ID").setDbKey("FILE_KEY"));
    IssueDto issue = IssueTesting.newDto(newRule(), file, project)
      .setIssueCreationDate(DateUtils.parseDate("2014-09-04"))
      .setIssueUpdateDate(DateUtils.parseDate("2017-12-04"))
      .setEffort(10L)
      .setStatus("OPEN")
      .setKee("82fd47d4-b650-4037-80bc-7b112bd4eac2")
      .setSeverity("MAJOR");
    db.issueDao().insert(session, issue);
    session.commit();
    indexIssues();

    userSessionRule.logIn("john");
    wsTester.newGetRequest(CONTROLLER_ISSUES, ACTION_SEARCH)
      .setParam("resolved", "false")
      .setParam(WebService.Param.FACETS, "statuses,severities,resolutions,projectUuids,rules,fileUuids,assignees,languages,actionPlans")
      .setParam("facetMode", FACET_MODE_EFFORT)
      .execute()
      .assertJson(this.getClass(), "display_facets_effort.json");
  }

  @Test
  public void display_zero_valued_facets_for_selected_items() throws Exception {
    ComponentDto project = insertComponent(ComponentTesting.newPublicProjectDto(otherOrganization1, "PROJECT_ID").setDbKey("PROJECT_KEY"));
    indexPermissions();
    ComponentDto file = insertComponent(ComponentTesting.newFileDto(project, null, "FILE_ID").setDbKey("FILE_KEY"));
    IssueDto issue = IssueTesting.newDto(newRule(), file, project)
      .setIssueCreationDate(DateUtils.parseDate("2014-09-04"))
      .setIssueUpdateDate(DateUtils.parseDate("2017-12-04"))
      .setEffort(10L)
      .setStatus("OPEN")
      .setKee("82fd47d4-b650-4037-80bc-7b112bd4eac2")
      .setSeverity("MAJOR");
    db.issueDao().insert(session, issue);
    session.commit();
    indexIssues();

    userSessionRule.logIn("john");
    wsTester.newGetRequest(CONTROLLER_ISSUES, ACTION_SEARCH)
      .setParam("resolved", "false")
      .setParam("severities", "MAJOR,MINOR")
      .setParam("languages", "xoo,polop,palap")
      .setParam(WebService.Param.FACETS, "statuses,severities,resolutions,projectUuids,rules,fileUuids,assignees,assigned_to_me,languages,actionPlans")
      .execute()
      .assertJson(this.getClass(), "display_zero_facets.json");
  }

  @Test
  public void assignedToMe_facet_must_escape_login_of_authenticated_user() throws Exception {
    // login looks like an invalid regexp
    userSessionRule.logIn("foo[");

    // should not fail
    wsTester.newGetRequest(CONTROLLER_ISSUES, ACTION_SEARCH)
      .setParam(WebService.Param.FACETS, "assigned_to_me")
      .execute()
      .assertJson(this.getClass(), "assignedToMe_facet_must_escape_login_of_authenticated_user.json");

  }

  @Test
  public void filter_by_assigned_to_me() throws Exception {
    db.userDao().insert(session, new UserDto().setLogin("john").setName("John").setEmail("john@email.com"));

    ComponentDto project = insertComponent(ComponentTesting.newPublicProjectDto(defaultOrganization, "PROJECT_ID").setDbKey("PROJECT_KEY"));
    indexPermissions();
    ComponentDto file = insertComponent(ComponentTesting.newFileDto(project, null, "FILE_ID").setDbKey("FILE_KEY"));
    RuleDto rule = newRule();
    IssueDto issue1 = IssueTesting.newDto(rule, file, project)
      .setIssueCreationDate(DateUtils.parseDate("2014-09-04"))
      .setIssueUpdateDate(DateUtils.parseDate("2017-12-04"))
      .setEffort(10L)
      .setStatus("OPEN")
      .setKee("82fd47d4-b650-4037-80bc-7b112bd4eac2")
      .setSeverity("MAJOR")
      .setAssignee("john");
    IssueDto issue2 = IssueTesting.newDto(rule, file, project)
      .setIssueCreationDate(DateUtils.parseDate("2014-09-04"))
      .setIssueUpdateDate(DateUtils.parseDate("2017-12-04"))
      .setEffort(10L)
      .setStatus("OPEN")
      .setKee("7b112bd4-b650-4037-80bc-82fd47d4eac2")
      .setSeverity("MAJOR")
      .setAssignee("alice");
    IssueDto issue3 = IssueTesting.newDto(rule, file, project)
      .setIssueCreationDate(DateUtils.parseDate("2014-09-04"))
      .setIssueUpdateDate(DateUtils.parseDate("2017-12-04"))
      .setEffort(10L)
      .setStatus("OPEN")
      .setKee("82fd47d4-4037-b650-80bc-7b112bd4eac2")
      .setSeverity("MAJOR");
    db.issueDao().insert(session, issue1, issue2, issue3);
    session.commit();
    indexIssues();

    userSessionRule.logIn("john");
    wsTester.newGetRequest(CONTROLLER_ISSUES, ACTION_SEARCH)
      .setParam("resolved", "false")
      .setParam("assignees", "__me__")
      .setParam(WebService.Param.FACETS, "assignees,assigned_to_me")
      .execute()
      .assertJson(this.getClass(), "filter_by_assigned_to_me.json");
  }

  @Test
  public void filter_by_assigned_to_me_unauthenticated() throws Exception {
    userSessionRule.logIn();

    ComponentDto project = insertComponent(ComponentTesting.newPublicProjectDto(otherOrganization1, "PROJECT_ID").setDbKey("PROJECT_KEY"));
    indexPermissions();
    ComponentDto file = insertComponent(ComponentTesting.newFileDto(project, null, "FILE_ID").setDbKey("FILE_KEY"));
    RuleDto rule = newRule();
    IssueDto issue1 = IssueTesting.newDto(rule, file, project)
      .setStatus("OPEN")
      .setKee("82fd47d4-b650-4037-80bc-7b112bd4eac2")
      .setAssignee("john");
    IssueDto issue2 = IssueTesting.newDto(rule, file, project)
      .setStatus("OPEN")
      .setKee("7b112bd4-b650-4037-80bc-82fd47d4eac2")
      .setAssignee("alice");
    IssueDto issue3 = IssueTesting.newDto(rule, file, project)
      .setStatus("OPEN")
      .setKee("82fd47d4-4037-b650-80bc-7b112bd4eac2");
    db.issueDao().insert(session, issue1, issue2, issue3);
    session.commit();
    indexIssues();

    wsTester.newGetRequest(CONTROLLER_ISSUES, ACTION_SEARCH)
      .setParam("resolved", "false")
      .setParam("assignees", "__me__")
      .execute()
      .assertJson(this.getClass(), "empty_result.json");
  }

  @Test
  public void assigned_to_me_facet_is_sticky_relative_to_assignees() throws Exception {
    db.userDao().insert(session, new UserDto().setLogin("alice").setName("Alice").setEmail("alice@email.com"));

    ComponentDto project = insertComponent(ComponentTesting.newPublicProjectDto(otherOrganization2, "PROJECT_ID").setDbKey("PROJECT_KEY"));
    indexPermissions();
    ComponentDto file = insertComponent(ComponentTesting.newFileDto(project, null, "FILE_ID").setDbKey("FILE_KEY"));
    RuleDto rule = newRule();
    IssueDto issue1 = IssueTesting.newDto(rule, file, project)
      .setIssueCreationDate(DateUtils.parseDate("2014-09-04"))
      .setIssueUpdateDate(DateUtils.parseDate("2017-12-04"))
      .setEffort(10L)
      .setStatus("OPEN")
      .setKee("82fd47d4-b650-4037-80bc-7b112bd4eac2")
      .setSeverity("MAJOR")
      .setAssignee("john-bob.polop");
    IssueDto issue2 = IssueTesting.newDto(rule, file, project)
      .setIssueCreationDate(DateUtils.parseDate("2014-09-04"))
      .setIssueUpdateDate(DateUtils.parseDate("2017-12-04"))
      .setEffort(10L)
      .setStatus("OPEN")
      .setKee("7b112bd4-b650-4037-80bc-82fd47d4eac2")
      .setSeverity("MAJOR")
      .setAssignee("alice");
    IssueDto issue3 = IssueTesting.newDto(rule, file, project)
      .setIssueCreationDate(DateUtils.parseDate("2014-09-04"))
      .setIssueUpdateDate(DateUtils.parseDate("2017-12-04"))
      .setEffort(10L)
      .setStatus("OPEN")
      .setKee("82fd47d4-4037-b650-80bc-7b112bd4eac2")
      .setSeverity("MAJOR");
    db.issueDao().insert(session, issue1, issue2, issue3);
    session.commit();
    indexIssues();

    userSessionRule.logIn("john-bob.polop");
    wsTester.newGetRequest(CONTROLLER_ISSUES, ACTION_SEARCH)
      .setParam("resolved", "false")
      .setParam("assignees", "alice")
      .setParam(WebService.Param.FACETS, "assignees,assigned_to_me")
      .execute()
      .assertJson(this.getClass(), "assigned_to_me_facet_sticky.json");
  }

  @Test
  public void sort_by_updated_at() throws Exception {
    RuleDto rule = newRule();
    ComponentDto project = insertComponent(ComponentTesting.newPublicProjectDto(otherOrganization2, "PROJECT_ID").setDbKey("PROJECT_KEY"));
    indexPermissions();
    ComponentDto file = insertComponent(ComponentTesting.newFileDto(project, null, "FILE_ID").setDbKey("FILE_KEY"));
    db.issueDao().insert(session, IssueTesting.newDto(rule, file, project)
      .setKee("82fd47d4-b650-4037-80bc-7b112bd4eac1")
      .setIssueUpdateDate(DateUtils.parseDateTime("2014-11-02T00:00:00+0100")));
    db.issueDao().insert(session, IssueTesting.newDto(rule, file, project)
      .setKee("82fd47d4-b650-4037-80bc-7b112bd4eac2")
      .setIssueUpdateDate(DateUtils.parseDateTime("2014-11-01T00:00:00+0100")));
    db.issueDao().insert(session, IssueTesting.newDto(rule, file, project)
      .setKee("82fd47d4-b650-4037-80bc-7b112bd4eac3")
      .setIssueUpdateDate(DateUtils.parseDateTime("2014-11-03T00:00:00+0100")));
    session.commit();
    indexIssues();

    wsTester.newGetRequest(CONTROLLER_ISSUES, ACTION_SEARCH)
      .setParam("sort", IssueQuery.SORT_BY_UPDATE_DATE)
      .setParam("asc", "false")
      .execute()
      .assertJson(this.getClass(), "sort_by_updated_at.json");
  }

  @Test
  public void paging() throws Exception {
    RuleDto rule = newRule();
    ComponentDto project = insertComponent(ComponentTesting.newPublicProjectDto(otherOrganization1, "PROJECT_ID").setDbKey("PROJECT_KEY"));
    indexPermissions();
    ComponentDto file = insertComponent(ComponentTesting.newFileDto(project, null, "FILE_ID").setDbKey("FILE_KEY"));
    for (int i = 0; i < 12; i++) {
      IssueDto issue = IssueTesting.newDto(rule, file, project);
      db.issueDao().insert(session, issue);
    }
    session.commit();
    indexIssues();

    wsTester.newGetRequest(CONTROLLER_ISSUES, ACTION_SEARCH)
      .setParam(WebService.Param.PAGE, "2")
      .setParam(WebService.Param.PAGE_SIZE, "9")
      .execute()
      .assertJson(this.getClass(), "paging.json");
  }

  @Test
  public void paging_with_page_size_to_minus_one() throws Exception {
    RuleDto rule = newRule();
    ComponentDto project = insertComponent(ComponentTesting.newPublicProjectDto(otherOrganization2, "PROJECT_ID").setDbKey("PROJECT_KEY"));
    indexPermissions();
    ComponentDto file = insertComponent(ComponentTesting.newFileDto(project, null, "FILE_ID").setDbKey("FILE_KEY"));
    for (int i = 0; i < 12; i++) {
      IssueDto issue = IssueTesting.newDto(rule, file, project);
      db.issueDao().insert(session, issue);
    }
    session.commit();
    indexIssues();

    wsTester.newGetRequest(CONTROLLER_ISSUES, ACTION_SEARCH)
      .setParam(WebService.Param.PAGE, "1")
      .setParam(WebService.Param.PAGE_SIZE, "-1")
      .execute()
      .assertJson(this.getClass(), "paging_with_page_size_to_minus_one.json");
  }

  @Test
  public void deprecated_paging() throws Exception {
    RuleDto rule = newRule();
    ComponentDto project = insertComponent(ComponentTesting.newPublicProjectDto(defaultOrganization, "PROJECT_ID").setDbKey("PROJECT_KEY"));
    indexPermissions();
    ComponentDto file = insertComponent(ComponentTesting.newFileDto(project, null, "FILE_ID").setDbKey("FILE_KEY"));
    for (int i = 0; i < 12; i++) {
      IssueDto issue = IssueTesting.newDto(rule, file, project);
      db.issueDao().insert(session, issue);
    }
    session.commit();
    indexIssues();

    wsTester.newGetRequest(CONTROLLER_ISSUES, ACTION_SEARCH)
      .setParam(PARAM_PAGE_INDEX, "2")
      .setParam(PARAM_PAGE_SIZE, "9")
      .execute()
      .assertJson(this.getClass(), "deprecated_paging.json");
  }

  @Test
  public void default_page_size_is_100() throws Exception {
    wsTester.newGetRequest(CONTROLLER_ISSUES, ACTION_SEARCH)
      .execute()
      .assertJson(this.getClass(), "default_page_size_is_100.json");
  }

  @Test
  public void display_deprecated_debt_fields() throws Exception {
    ComponentDto project = insertComponent(ComponentTesting.newPublicProjectDto(otherOrganization1, "PROJECT_ID").setDbKey("PROJECT_KEY"));
    indexPermissions();
    ComponentDto file = insertComponent(ComponentTesting.newFileDto(project, null, "FILE_ID").setDbKey("FILE_KEY"));
    IssueDto issue = IssueTesting.newDto(newRule(), file, project)
      .setIssueCreationDate(DateUtils.parseDate("2014-09-04"))
      .setIssueUpdateDate(DateUtils.parseDate("2017-12-04"))
      .setEffort(10L)
      .setStatus("OPEN")
      .setKee("82fd47d4-b650-4037-80bc-7b112bd4eac2")
      .setSeverity("MAJOR");
    db.issueDao().insert(session, issue);
    session.commit();
    indexIssues();

    userSessionRule.logIn("john");
    wsTester.newGetRequest(CONTROLLER_ISSUES, ACTION_SEARCH)
      .setParam("resolved", "false")
      .setParam(WebService.Param.FACETS, "severities")
      .setParam("facetMode", DEPRECATED_FACET_MODE_DEBT)
      .execute()
      .assertJson(this.getClass(), "display_deprecated_debt_fields.json");
  }

  @Test
  public void fail_when_invalid_format() throws Exception {
    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage("Date 'wrong-date-input' cannot be parsed as either a date or date+time");

    wsTester.newGetRequest(CONTROLLER_ISSUES, ACTION_SEARCH)
      .setParam(PARAM_CREATED_AFTER, "wrong-date-input")
      .execute();
  }

  private RuleDto newRule() {
    RuleDto rule = RuleTesting.newXooX1()
      .setName("Rule name")
      .setDescription("Rule desc")
      .setStatus(RuleStatus.READY);
    dbTester.rules().insert(rule.getDefinition());
    return rule;
  }

  private void indexPermissions() {
    permissionIndexer.indexOnStartup(permissionIndexer.getIndexTypes());
  }

  private void indexIssues() {
    issueIndexer.indexOnStartup(issueIndexer.getIndexTypes());
  }

  private void grantPermissionToAnyone(ComponentDto project, String permission) {
    db.groupPermissionDao().insert(session,
      new GroupPermissionDto()
        .setOrganizationUuid(project.getOrganizationUuid())
        .setGroupId(null)
        .setResourceId(project.getId())
        .setRole(permission));
    session.commit();
    userSessionRule.logIn().addProjectPermission(permission, project);
  }

  private ComponentDto insertComponent(ComponentDto component) {
    db.componentDao().insert(session, component);
    session.commit();
    return component;
  }
}
