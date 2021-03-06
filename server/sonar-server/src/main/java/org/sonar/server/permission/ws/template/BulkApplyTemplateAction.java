/*
 * SonarQube
 * Copyright (C) 2009-2016 SonarSource SA
 * mailto:contact AT sonarsource DOT com
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

package org.sonar.server.permission.ws.template;

import com.google.common.collect.Collections2;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import org.sonar.api.i18n.I18n;
import org.sonar.api.resources.ResourceTypes;
import org.sonar.api.server.ws.Request;
import org.sonar.api.server.ws.Response;
import org.sonar.api.server.ws.WebService;
import org.sonar.api.server.ws.WebService.Param;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.component.ComponentDto;
import org.sonar.db.component.ComponentQuery;
import org.sonar.db.permission.template.PermissionTemplateDto;
import org.sonar.server.permission.PermissionService;
import org.sonar.server.permission.ProjectId;
import org.sonar.server.permission.ws.PermissionWsSupport;
import org.sonar.server.permission.ws.PermissionsWsAction;
import org.sonar.server.user.UserSession;
import org.sonarqube.ws.client.permission.BulkApplyTemplateWsRequest;

import static org.sonar.server.component.ResourceTypeFunctions.RESOURCE_TYPE_TO_QUALIFIER;
import static org.sonar.server.permission.PermissionPrivilegeChecker.checkProjectAdmin;
import static org.sonar.server.permission.ws.PermissionsWsParametersBuilder.createTemplateParameters;
import static org.sonar.server.permission.ws.template.WsTemplateRef.newTemplateRef;
import static org.sonar.server.ws.WsParameterBuilder.QualifierParameterContext.newQualifierParameterContext;
import static org.sonar.server.ws.WsParameterBuilder.createRootQualifierParameter;
import static org.sonarqube.ws.client.permission.PermissionsWsParameters.PARAM_ORGANIZATION_KEY;
import static org.sonarqube.ws.client.permission.PermissionsWsParameters.PARAM_QUALIFIER;
import static org.sonarqube.ws.client.permission.PermissionsWsParameters.PARAM_TEMPLATE_ID;
import static org.sonarqube.ws.client.permission.PermissionsWsParameters.PARAM_TEMPLATE_NAME;

public class BulkApplyTemplateAction implements PermissionsWsAction {

  private final DbClient dbClient;
  private final UserSession userSession;
  private final PermissionService permissionService;
  private final PermissionWsSupport wsSupport;
  private final I18n i18n;
  private final ResourceTypes resourceTypes;

  public BulkApplyTemplateAction(DbClient dbClient, UserSession userSession, PermissionService permissionService, PermissionWsSupport wsSupport, I18n i18n,
    ResourceTypes resourceTypes) {
    this.dbClient = dbClient;
    this.userSession = userSession;
    this.permissionService = permissionService;
    this.wsSupport = wsSupport;
    this.i18n = i18n;
    this.resourceTypes = resourceTypes;
  }

  @Override
  public void define(WebService.NewController context) {
    WebService.NewAction action = context.createAction("bulk_apply_template")
      .setDescription("Apply a permission template to several projects.<br />" +
        "The template id or name must be provided.<br />" +
        "It requires administration permissions to access.")
      .setPost(true)
      .setSince("5.5")
      .setHandler(this);

    action.createParam(Param.TEXT_QUERY)
      .setDescription("Limit search to: <ul>" +
        "<li>project names that contain the supplied string</li>" +
        "<li>project keys that are exactly the same as the supplied string</li>" +
        "</ul>")
      .setExampleValue("apac");
    createRootQualifierParameter(action, newQualifierParameterContext(i18n, resourceTypes));
    createTemplateParameters(action);
  }

  @Override
  public void handle(Request request, Response response) throws Exception {
    doHandle(toBulkApplyTemplateWsRequest(request));
    response.noContent();
  }

  private void doHandle(BulkApplyTemplateWsRequest request) {
    try (DbSession dbSession = dbClient.openSession(false)) {
      PermissionTemplateDto template = wsSupport.findTemplate(dbSession, newTemplateRef(
        request.getTemplateId(), request.getOrganization(), request.getTemplateName()));
      ComponentQuery componentQuery = ComponentQuery.builder()
        .setNameOrKeyQuery(request.getQuery())
        .setQualifiers(qualifiers(request.getQualifier()))
        .build();
      List<ComponentDto> projects = dbClient.componentDao().selectByQuery(dbSession, componentQuery, 0, Integer.MAX_VALUE);

      for (ComponentDto project : projects) {
        ProjectId projectId = new ProjectId(project);
        checkProjectAdmin(userSession, template.getOrganizationUuid(), Optional.of(projectId));
      }
      permissionService.apply(dbSession, template, projects);
    }
  }

  private String[] qualifiers(@Nullable String qualifier) {
    return qualifier == null
      ? Collections2.transform(resourceTypes.getRoots(), RESOURCE_TYPE_TO_QUALIFIER).toArray(new String[resourceTypes.getRoots().size()])
      : (new String[] {qualifier});
  }

  private static BulkApplyTemplateWsRequest toBulkApplyTemplateWsRequest(Request request) {
    return new BulkApplyTemplateWsRequest()
      .setTemplateId(request.param(PARAM_TEMPLATE_ID))
      .setOrganization(request.param(PARAM_ORGANIZATION_KEY))
      .setTemplateName(request.param(PARAM_TEMPLATE_NAME))
      .setQualifier(request.param(PARAM_QUALIFIER))
      .setQuery(request.param(Param.TEXT_QUERY));
  }
}
