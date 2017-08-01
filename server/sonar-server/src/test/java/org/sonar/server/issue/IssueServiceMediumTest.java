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
package org.sonar.server.issue;

import com.google.common.collect.ImmutableSet;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.sonar.api.issue.Issue;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.component.ComponentDao;
import org.sonar.db.component.ComponentDto;
import org.sonar.db.component.ComponentTesting;
import org.sonar.db.issue.IssueDao;
import org.sonar.db.issue.IssueDto;
import org.sonar.db.issue.IssueTesting;
import org.sonar.db.organization.OrganizationDao;
import org.sonar.db.organization.OrganizationDto;
import org.sonar.db.organization.OrganizationTesting;
import org.sonar.db.rule.RuleDao;
import org.sonar.db.rule.RuleDto;
import org.sonar.db.rule.RuleTesting;
import org.sonar.server.issue.index.IssueIndex;
import org.sonar.server.issue.index.IssueIndexer;
import org.sonar.server.permission.index.PermissionIndexer;
import org.sonar.server.rule.index.RuleIndexer;
import org.sonar.server.tester.ServerTester;
import org.sonar.server.tester.UserSessionRule;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

@Ignore("because relies on currently broken SearchServer (through ServerTester)")
public class IssueServiceMediumTest {

  @ClassRule
  public static ServerTester tester = new ServerTester().withStartupTasks().withEsIndexes();
  @Rule
  public UserSessionRule userSessionRule = UserSessionRule.forServerTester(tester);

  private DbClient db = tester.get(DbClient.class);
  private IssueIndex issueIndex;
  private DbSession session;
  private IssueService service;
  private RuleIndexer ruleIndexer;

  @Before
  public void setUp() {
    tester.clearDbAndIndexes();
    issueIndex = tester.get(IssueIndex.class);
    session = db.openSession(false);
    service = tester.get(IssueService.class);
    ruleIndexer = tester.get(RuleIndexer.class);
  }

  @After
  public void after() {
    session.close();
  }

  @Test
  public void list_component_tags() {
    RuleDto rule = newRule();
    ComponentDto project = newPublicProject();
    ComponentDto file = newFile(project);
    saveIssue(IssueTesting.newDto(rule, file, project).setTags(ImmutableSet.of("convention", "java8", "bug")));
    saveIssue(IssueTesting.newDto(rule, file, project).setTags(ImmutableSet.of("convention", "bug")));
    saveIssue(IssueTesting.newDto(rule, file, project));
    saveIssue(IssueTesting.newDto(rule, file, project).setTags(ImmutableSet.of("convention", "java8", "bug")).setResolution(Issue.RESOLUTION_FIXED));
    saveIssue(IssueTesting.newDto(rule, file, project).setTags(ImmutableSet.of("convention")));

    assertThat(service.listTagsForComponent(projectQuery(project.uuid()), 5)).containsOnly(entry("convention", 3L), entry("bug", 2L), entry("java8", 1L));
    assertThat(service.listTagsForComponent(projectQuery(project.uuid()), 2)).contains(entry("convention", 3L), entry("bug", 2L)).doesNotContainEntry("java8", 1L);
    assertThat(service.listTagsForComponent(projectQuery("other"), 10)).isEmpty();
  }

  private IssueQuery projectQuery(String projectUuid) {
    return IssueQuery.builder().projectUuids(asList(projectUuid)).resolved(false).build();
  }

  private RuleDto newRule() {
    return newRule(RuleTesting.newXooX1());
  }

  private RuleDto newRule(RuleDto rule) {
    RuleDao ruleDao = tester.get(RuleDao.class);
    ruleDao.insert(session, rule.getDefinition());
    if (rule.getOrganizationUuid() != null) {
      ruleDao.insertOrUpdate(session, rule.getMetadata().setRuleId(rule.getId()));
    }
    session.commit();
    //FIXME ruleIndexer.commitAndIndex(dbSession, rule.getDefinition().getKey());
    return rule;
  }

  private ComponentDto newPublicProject() {
    OrganizationDto organization = OrganizationTesting.newOrganizationDto();
    tester.get(OrganizationDao.class).insert(session, organization, false);
    ComponentDto project = ComponentTesting.newPublicProjectDto(organization);
    tester.get(ComponentDao.class).insert(session, project);
    session.commit();

    indexPermissions();
    userSessionRule.logIn();

    return project;
  }

  private void indexPermissions() {
    PermissionIndexer permissionIndexer = tester.get(PermissionIndexer.class);
    permissionIndexer.indexOnStartup(permissionIndexer.getIndexTypes());
  }

  private ComponentDto newFile(ComponentDto project) {
    ComponentDto file = ComponentTesting.newFileDto(project, null);
    tester.get(ComponentDao.class).insert(session, file);
    session.commit();
    return file;
  }

  private IssueDto saveIssue(IssueDto issue) {
    tester.get(IssueDao.class).insert(session, issue);
    session.commit();
    tester.get(IssueIndexer.class).commitAndIndexIssues(session, asList(issue));
    return issue;
  }
}
