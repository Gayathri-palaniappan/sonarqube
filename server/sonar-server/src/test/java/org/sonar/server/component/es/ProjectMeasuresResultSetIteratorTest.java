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

package org.sonar.server.component.es;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import java.util.Date;
import java.util.Map;
import javax.annotation.Nullable;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.sonar.api.measures.Metric;
import org.sonar.api.utils.System2;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.DbTester;
import org.sonar.db.component.ComponentDbTester;
import org.sonar.db.component.ComponentDto;
import org.sonar.db.component.SnapshotDto;
import org.sonar.db.measure.MeasureDto;
import org.sonar.db.measure.MeasureTesting;
import org.sonar.db.metric.MetricDto;
import org.sonar.db.metric.MetricTesting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.sonar.api.measures.Metric.ValueType.DATA;
import static org.sonar.api.measures.Metric.ValueType.DISTRIB;
import static org.sonar.api.measures.Metric.ValueType.INT;
import static org.sonar.api.measures.Metric.ValueType.LEVEL;
import static org.sonar.db.component.ComponentTesting.newDeveloper;
import static org.sonar.db.component.ComponentTesting.newProjectDto;
import static org.sonar.db.component.ComponentTesting.newView;
import static org.sonar.db.component.SnapshotTesting.newAnalysis;
import static org.sonar.server.computation.task.projectanalysis.measure.Measure.Level.WARN;

public class ProjectMeasuresResultSetIteratorTest {

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  @Rule
  public DbTester dbTester = DbTester.create(System2.INSTANCE);

  DbClient dbClient = dbTester.getDbClient();
  DbSession dbSession = dbTester.getSession();

  ComponentDbTester componentDbTester = new ComponentDbTester(dbTester);

  @Test
  public void return_project_measure() {
    MetricDto metric1 = insertIntMetric("ncloc");
    MetricDto metric2 = insertIntMetric("coverage");
    ComponentDto project = newProjectDto().setKey("Project-Key").setName("Project Name");
    SnapshotDto analysis = componentDbTester.insertProjectAndSnapshot(project);
    insertMeasure(project, analysis, metric1, 10d);
    insertMeasure(project, analysis, metric2, 20d);

    Map<String, ProjectMeasuresDoc> docsById = createResultSetAndReturnDocsById();

    assertThat(docsById).hasSize(1);
    ProjectMeasuresDoc doc = docsById.get(project.uuid());
    assertThat(doc).isNotNull();
    assertThat(doc.getId()).isEqualTo(project.uuid());
    assertThat(doc.getKey()).isEqualTo("Project-Key");
    assertThat(doc.getName()).isEqualTo("Project Name");
    assertThat(doc.getAnalysedAt()).isNotNull().isEqualTo(new Date(analysis.getCreatedAt()));
    assertThat(doc.getMeasures()).containsOnly(
      ImmutableMap.of("key", "ncloc", "value", 10d),
      ImmutableMap.of("key", "coverage", "value", 20d));
  }

  @Test
  public void return_project_measure_having_leak() throws Exception {
    MetricDto metric = insertIntMetric("new_lines");
    ComponentDto project = newProjectDto();
    SnapshotDto analysis = componentDbTester.insertProjectAndSnapshot(project);
    insertMeasureOnLeak(project, analysis, metric, 10d);

    Map<String, ProjectMeasuresDoc> docsById = createResultSetAndReturnDocsById();

    assertThat(docsById).hasSize(1);
    ProjectMeasuresDoc doc = docsById.get(project.uuid());
    assertThat(doc).isNotNull();
    assertThat(doc.getMeasures()).containsOnly(ImmutableMap.of("key", "new_lines", "value", 10d));
  }

  @Test
  public void return_quality_gate_status_measure() throws Exception {
    MetricDto metric = insertMetric("alert_status", LEVEL);
    ComponentDto project = newProjectDto();
    SnapshotDto analysis = componentDbTester.insertProjectAndSnapshot(project);
    insertMeasure(project, analysis, metric, WARN.name());

    Map<String, ProjectMeasuresDoc> docsById = createResultSetAndReturnDocsById();

    assertThat(docsById).hasSize(1);
    assertThat(docsById.get(project.uuid()).getQualityGate()).isEqualTo("WARN");
  }

  @Test
  public void does_not_return_none_numeric_metrics() throws Exception {
    MetricDto dataMetric = insertMetric("data", DATA);
    MetricDto distribMetric = insertMetric("distrib", DISTRIB);
    ComponentDto project = newProjectDto();
    SnapshotDto analysis = componentDbTester.insertProjectAndSnapshot(project);
    insertMeasure(project, analysis, dataMetric, 10d);
    insertMeasure(project, analysis, distribMetric, 10d);

    Map<String, ProjectMeasuresDoc> docsById = createResultSetAndReturnDocsById();
    assertThat(docsById).hasSize(1);
    ProjectMeasuresDoc doc = docsById.get(project.uuid());
    assertThat(doc.getMeasures()).isEmpty();
  }

  @Test
  public void does_not_return_disabled_and_hidden_metrics() throws Exception {
    MetricDto disabledMetric = insertMetric("disabled", false, false, INT);
    MetricDto hiddenMetric = insertMetric("hidden", true, true, INT);
    ComponentDto project = newProjectDto();
    SnapshotDto analysis = componentDbTester.insertProjectAndSnapshot(project);
    insertMeasure(project, analysis, disabledMetric, 10d);
    insertMeasure(project, analysis, hiddenMetric, 10d);

    Map<String, ProjectMeasuresDoc> docsById = createResultSetAndReturnDocsById();
    assertThat(docsById).hasSize(1);
    ProjectMeasuresDoc doc = docsById.get(project.uuid());
    assertThat(doc.getMeasures()).isEmpty();
  }

  @Test
  public void fail_when_measure_return_no_value() throws Exception {
    MetricDto metric = insertIntMetric("new_lines");
    ComponentDto project = newProjectDto();
    SnapshotDto analysis = componentDbTester.insertProjectAndSnapshot(project);
    insertMeasure(project, analysis, metric, 10d);

    expectedException.expect(IllegalStateException.class);
    createResultSetAndReturnDocsById();
  }

  @Test
  public void return_many_project_measures() {
    componentDbTester.insertProjectAndSnapshot(newProjectDto());
    componentDbTester.insertProjectAndSnapshot(newProjectDto());
    componentDbTester.insertProjectAndSnapshot(newProjectDto());

    assertThat(createResultSetAndReturnDocsById()).hasSize(3);
  }

  @Test
  public void return_project_without_analysis() throws Exception {
    ComponentDto project = componentDbTester.insertComponent(newProjectDto());
    dbClient.snapshotDao().insert(dbSession, newAnalysis(project).setLast(false));
    dbSession.commit();

    Map<String, ProjectMeasuresDoc> docsById = createResultSetAndReturnDocsById();

    assertThat(docsById).hasSize(1);
    ProjectMeasuresDoc doc = docsById.get(project.uuid());
    assertThat(doc.getAnalysedAt()).isNull();
  }

  @Test
  public void does_not_return_non_active_projects() throws Exception {
    // Disabled project
    componentDbTester.insertProjectAndSnapshot(newProjectDto().setEnabled(false));
    // Disabled project with analysis
    ComponentDto project = componentDbTester.insertComponent(newProjectDto().setEnabled(false));
    dbClient.snapshotDao().insert(dbSession, newAnalysis(project));

    // A view
    componentDbTester.insertProjectAndSnapshot(newView());

    // A developer
    componentDbTester.insertProjectAndSnapshot(newDeveloper("dev"));

    dbSession.commit();

    assertResultSetIsEmpty();
  }

  @Test
  public void return_only_docs_from_given_project() throws Exception {
    ComponentDto project = newProjectDto();
    SnapshotDto analysis = componentDbTester.insertProjectAndSnapshot(project);
    componentDbTester.insertProjectAndSnapshot(newProjectDto());
    componentDbTester.insertProjectAndSnapshot(newProjectDto());

    Map<String, ProjectMeasuresDoc> docsById = createResultSetAndReturnDocsById(0L, project.uuid());

    assertThat(docsById).hasSize(1);
    ProjectMeasuresDoc doc = docsById.get(project.uuid());
    assertThat(doc).isNotNull();
    assertThat(doc.getId()).isEqualTo(project.uuid());
    assertThat(doc.getKey()).isNotNull().isEqualTo(project.getKey());
    assertThat(doc.getName()).isNotNull().isEqualTo(project.name());
    assertThat(doc.getAnalysedAt()).isNotNull().isEqualTo(new Date(analysis.getCreatedAt()));
  }

  @Test
  public void return_only_docs_after_date() throws Exception {
    ComponentDto project1 = newProjectDto();
    dbClient.componentDao().insert(dbSession, project1);
    dbClient.snapshotDao().insert(dbSession, newAnalysis(project1).setCreatedAt(1_000_000L));
    ComponentDto project2 = newProjectDto();
    dbClient.componentDao().insert(dbSession, project2);
    dbClient.snapshotDao().insert(dbSession, newAnalysis(project2).setCreatedAt(2_000_000L));
    dbSession.commit();

    Map<String, ProjectMeasuresDoc> docsById = createResultSetAndReturnDocsById(1_500_000L, null);

    assertThat(docsById).hasSize(1);
    assertThat(docsById.get(project2.uuid())).isNotNull();
  }

  @Test
  public void return_nothing_on_unknown_project() throws Exception {
    componentDbTester.insertProjectAndSnapshot(newProjectDto());

    Map<String, ProjectMeasuresDoc> docsById = createResultSetAndReturnDocsById(0L, "UNKNOWN");

    assertThat(docsById).isEmpty();
  }

  private Map<String, ProjectMeasuresDoc> createResultSetAndReturnDocsById() {
    return createResultSetAndReturnDocsById(0L, null);
  }

  private Map<String, ProjectMeasuresDoc> createResultSetAndReturnDocsById(long date, @Nullable String projectUuid) {
    ProjectMeasuresResultSetIterator it = ProjectMeasuresResultSetIterator.create(dbTester.getDbClient(), dbTester.getSession(), date, projectUuid);
    Map<String, ProjectMeasuresDoc> docsById = Maps.uniqueIndex(it, ProjectMeasuresDoc::getId);
    it.close();
    return docsById;
  }

  private void assertResultSetIsEmpty() {
    assertThat(createResultSetAndReturnDocsById()).isEmpty();
  }

  private MetricDto insertIntMetric(String metricKey) {
    return insertMetric(metricKey, true, false, INT);
  }

  private MetricDto insertMetric(String metricKey, Metric.ValueType type) {
    return insertMetric(metricKey, true, false, type);
  }

  private MetricDto insertMetric(String metricKey, boolean enabled, boolean hidden, Metric.ValueType type) {
    MetricDto metric = dbClient.metricDao().insert(dbSession,
      MetricTesting.newMetricDto()
        .setKey(metricKey)
        .setEnabled(enabled)
        .setHidden(hidden)
        .setValueType(type.name()));
    dbSession.commit();
    return metric;
  }

  private MeasureDto insertMeasure(ComponentDto project, SnapshotDto analysis, MetricDto metric, double value) {
    return insertMeasure(project, analysis, metric, value, null);
  }

  private MeasureDto insertMeasureOnLeak(ComponentDto project, SnapshotDto analysis, MetricDto metric, double value) {
    return insertMeasure(project, analysis, metric, null, value);
  }

  private MeasureDto insertMeasure(ComponentDto project, SnapshotDto analysis, MetricDto metric, String value) {
    return insertMeasure(MeasureTesting.newMeasureDto(metric, project, analysis).setData(value));
  }

  private MeasureDto insertMeasure(ComponentDto project, SnapshotDto analysis, MetricDto metric, @Nullable Double value, @Nullable Double leakValue) {
    return insertMeasure(MeasureTesting.newMeasureDto(metric, project, analysis).setValue(value).setVariation(1, leakValue));
  }

  private MeasureDto insertMeasure(MeasureDto measure) {
    dbClient.measureDao().insert(dbSession, measure);
    dbSession.commit();
    return measure;
  }

}
