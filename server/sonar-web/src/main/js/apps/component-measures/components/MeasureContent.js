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
// @flow
import React from 'react';
import moment from 'moment';
import Breadcrumbs from './Breadcrumbs';
import Favorite from '../../../components/controls/Favorite';
import FilesView from './drilldown/FilesView';
import MeasureHeader from './MeasureHeader';
import MeasureViewSelect from './MeasureViewSelect';
import MetricNotFound from './MetricNotFound';
import PageActions from './PageActions';
import SourceViewer from '../../../components/SourceViewer/SourceViewer';
import { getComponentTree } from '../../../api/components';
import { complementary } from '../config/complementary';
import { enhanceComponent, isFileType } from '../utils';
import { isDiffMetric } from '../../../helpers/measures';
import type { Component, ComponentEnhanced, Paging, Period } from '../types';
import type { MeasureEnhanced } from '../../../components/measure/types';
import type { Metric } from '../../../store/metrics/actions';

type Props = {
  className?: string,
  component: Component,
  currentUser: { isLoggedIn: boolean },
  loading: boolean,
  leakPeriod?: Period,
  measure: ?MeasureEnhanced,
  metric: Metric,
  metrics: { [string]: Metric },
  rootComponent: Component,
  secondaryMeasure: ?MeasureEnhanced,
  updateLoading: ({ [string]: boolean }) => void,
  updateSelected: Component => void,
  updateView: string => void,
  view: string
};

type State = {
  components: Array<ComponentEnhanced>,
  metric: ?Metric,
  paging?: Paging
};

export default class MeasureContent extends React.PureComponent {
  mounted: boolean;
  props: Props;
  state: State = {
    components: [],
    metric: null,
    paging: null
  };

  componentDidMount() {
    this.mounted = true;
    this.fetchComponents(this.props);
  }

  componentWillReceiveProps(nextProps: Props) {
    if (nextProps.component !== this.props.component || nextProps.metric !== this.props.metric) {
      this.fetchComponents(nextProps);
    }
  }

  componentWillUnmount() {
    this.mounted = false;
  }

  getComponentRequestParams = (metric: Metric, options: Object = {}) => {
    const metricKeys = [metric.key, ...(complementary[metric.key] || [])];
    let opts: Object = {
      asc: metric.direction === 1,
      ps: 100,
      metricSortFilter: 'withMeasuresOnly',
      metricSort: metric.key
    };
    if (isDiffMetric(metric.key)) {
      opts = {
        ...opts,
        s: 'metricPeriod,name',
        metricPeriodSort: 1
      };
    } else {
      opts = {
        ...opts,
        s: 'metric,name'
      };
    }
    return { metricKeys, opts: { ...opts, ...options } };
  };

  fetchComponents = ({ component, metric, view }: Props) => {
    if (isFileType(component)) {
      return this.setState({ components: [], metric: null, paging: null });
    }

    const strategy = view === 'list' ? 'leaves' : 'children';
    const { metricKeys, opts } = this.getComponentRequestParams(metric);
    this.props.updateLoading({ components: true });
    getComponentTree(strategy, component.key, metricKeys, opts).then(
      r => {
        if (this.mounted) {
          this.setState({
            components: r.components.map(component => enhanceComponent(component, metric)),
            metric,
            paging: r.paging
          });
        }
        this.props.updateLoading({ components: false });
      },
      () => this.props.updateLoading({ components: false })
    );
  };

  fetchMoreComponents = () => {
    const { component, metric, view } = this.props;
    const { paging } = this.state;
    if (!paging) {
      return;
    }
    const strategy = view === 'list' ? 'leaves' : 'children';
    const { metricKeys, opts } = this.getComponentRequestParams(metric, {
      p: paging.pageIndex + 1
    });
    this.props.updateLoading({ components: true });
    getComponentTree(strategy, component.key, metricKeys, opts).then(
      r => {
        if (this.mounted) {
          this.setState(state => ({
            components: [
              ...state.components,
              ...r.components.map(component => enhanceComponent(component, metric))
            ],
            metric,
            paging: r.paging
          }));
        }
        this.props.updateLoading({ components: false });
      },
      () => this.props.updateLoading({ components: false })
    );
  };

  renderContent() {
    const { component, leakPeriod, view } = this.props;

    if (isFileType(component)) {
      const leakPeriodDate =
        isDiffMetric(this.props.metric.key) && leakPeriod != null
          ? moment(leakPeriod.date).toDate()
          : null;

      let filterLine;
      if (leakPeriodDate != null) {
        filterLine = line => {
          if (line.scmDate) {
            const scmDate = moment(line.scmDate).toDate();
            return scmDate >= leakPeriodDate;
          } else {
            return false;
          }
        };
      }
      return (
        <div className="measure-details-viewer">
          <SourceViewer component={component.key} filterLine={filterLine} />
        </div>
      );
    }

    const { metric } = this.state;
    if (metric == null) {
      return null;
    }

    if (['list', 'tree'].includes(view)) {
      return (
        <FilesView
          components={this.state.components}
          fetchMore={this.fetchMoreComponents}
          handleSelect={this.props.updateSelected}
          metric={metric}
          metrics={this.props.metrics}
          paging={this.state.paging}
        />
      );
    }
  }

  render() {
    const { component, currentUser, measure, metric, rootComponent, view } = this.props;
    const isLoggedIn = currentUser && currentUser.isLoggedIn;
    return (
      <div className={this.props.className}>
        <div className="layout-page-header-panel layout-page-main-header issues-main-header">
          <div className="layout-page-header-panel-inner layout-page-main-header-inner">
            <div className="layout-page-main-inner clearfix">
              <Breadcrumbs
                className="measure-breadcrumbs spacer-right text-ellipsis"
                component={component}
                handleSelect={this.props.updateSelected}
                rootComponent={rootComponent}
              />
              {component.key !== rootComponent.key &&
                isLoggedIn &&
                <Favorite
                  favorite={component.isFavorite === true}
                  component={component.key}
                  className="measure-favorite spacer-right"
                />}
              <MeasureViewSelect
                className="measure-view-select"
                metric={metric}
                handleViewChange={this.props.updateView}
                view={view}
              />
              <PageActions
                current={this.state.components.length}
                loading={this.props.loading}
                isFile={isFileType(component)}
                paging={this.state.paging}
                view={view}
              />
            </div>
          </div>
        </div>
        {metric == null && <MetricNotFound className="layout-page-main-inner" />}
        {metric != null &&
          measure != null &&
          <div className="layout-page-main-inner">
            <MeasureHeader
              component={component}
              leakPeriod={this.props.leakPeriod}
              measure={measure}
              secondaryMeasure={this.props.secondaryMeasure}
            />
            {this.renderContent()}
          </div>}
      </div>
    );
  }
}
