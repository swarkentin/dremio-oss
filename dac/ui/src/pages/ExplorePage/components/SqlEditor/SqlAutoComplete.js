/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import React, { Component } from 'react';

import Radium from 'radium';
import PropTypes from 'prop-types';
import Immutable from 'immutable';
import deepEqual from 'deep-equal';
import classNames from 'classnames';

import { connect } from 'react-redux';
import exploreUtils from 'utils/explore/exploreUtils';
import { splitFullPath, constructFullPath } from 'utils/pathUtils';

import Modal from 'components/Modals/Modal';
import SQLEditor from 'components/SQLEditor';

import DragTarget from 'components/DragComponents/DragTarget';
import Art from '@app/components/Art';

import localStorageUtils from '@app/utils/storageUtils/localStorageUtils';
import SelectContextForm from '../forms/SelectContextForm';

import './SqlAutoComplete.less';
import RefPicker from './components/RefPicker';

const DEFAULT_CONTEXT = '<none>';
@Radium
export class SqlAutoComplete extends Component { // todo: pull SQLEditor into this class (and rename)
  static propTypes = {
    onChange: PropTypes.func,
    onFunctionChange: PropTypes.func,
    pageType: PropTypes.oneOf(['details', 'recent']),
    defaultValue: PropTypes.string,
    isGrayed: PropTypes.bool,
    context: PropTypes.instanceOf(Immutable.List),
    errors: PropTypes.instanceOf(Immutable.List),
    name: PropTypes.string,
    sqlSize: PropTypes.number,
    funcHelpPanel: PropTypes.bool,
    changeQueryContext: PropTypes.func,
    style: PropTypes.object,
    dragType: PropTypes.string,
    autoCompleteEnabled: PropTypes.bool.isRequired,
    children: PropTypes.any,
    sidebarCollapsed: PropTypes.bool
  };

  static defaultProps = {
    sqlSize: 100
  };

  static contextTypes = {
    location: PropTypes.object.isRequired,
    router: PropTypes.object.isRequired
  };

  monacoEditorComponent = null;
  sqlAutoCompleteRef = null;

  constructor(props) {
    super(props);

    this.handleClickEditContext = this.handleClickEditContext.bind(this);
    this.renderSelectContextModal = this.renderSelectContextModal.bind(this);
    this.hideSelectContextModal = this.hideSelectContextModal.bind(this);
    this.updateContext = this.updateContext.bind(this);

    this.state = {
      showSelectContextModal: false,
      isContrast: localStorageUtils.getSqlThemeContrast(),
      funcHelpPanel: this.props.funcHelpPanel
    };
  }

  shouldComponentUpdate(nextProps, nextState, nextContext) {
    return (
      nextProps.defaultValue !== this.props.defaultValue ||
      nextProps.context !== this.props.context ||
      nextContext.location.query.version !== this.context.location.query.version ||
      nextProps.funcHelpPanel !== this.props.funcHelpPanel ||
      nextProps.isGrayed !== this.props.isGrayed ||
      nextProps.sqlSize !== this.props.sqlSize ||
      nextProps.autoCompleteEnabled !== this.props.autoCompleteEnabled ||
      nextProps.sidebarCollapsed !== this.props.sidebarCollapsed ||
      !deepEqual(nextState, this.state)
    );
  }

  handleDrop = ({ id, args }) => {
    // because we move the cursor as we drag around, we can simply insert at the current position in the editor (default)

    // duck-type check pending drag-n-drop revamp
    if (args !== undefined) {
      this.insertFunction(id, args);
    } else if (typeof id === 'string') {
      this.insertFieldName(id);
    } else {
      this.insertFullPath(id);
    }
  };

  getMonacoEditorInstance() {
    return this.sqlAutoCompleteRef.monacoEditorComponent.editor;
  }

  getMonaco() {
    return this.sqlAutoCompleteRef.monaco;
  }

  handleDragOver = (evt) => {
    const target = this.getMonacoEditorInstance().getTargetAtClientPoint(evt.clientX, evt.clientY);
    if (!target || !target.position) return; // no position if you drag over the rightmost part of the context UI
    this.getMonacoEditorInstance().setPosition(target.position);
    this.focus();
  };

  focus() {
    if (this.sqlAutoCompleteRef) {
      this.sqlAutoCompleteRef.focus();
    }
  }

  resetValue() {
    this.sqlAutoCompleteRef && this.sqlAutoCompleteRef.resetValue();
  }

  handleChange = () => {
    this.updateCode();
  };

  handleClickEditContext() {
    this.setState({ showSelectContextModal: true });
  }

  hideSelectContextModal() {
    this.setState({ showSelectContextModal: false });
  }

  updateContext(resource) {
    this.props.changeQueryContext(Immutable.fromJS(resource.context && splitFullPath(resource.context)));
    this.hideSelectContextModal();
  }

  insertFullPath(pathList, ranges) {
    const text = constructFullPath(pathList);
    this.insertAtRanges(text, ranges);
  }

  insertFieldName(name, ranges) {
    const text = exploreUtils.escapeFieldNameForSQL(name);
    this.insertAtRanges(text, ranges);
  }

  insertFunction(name, args, ranges = this.getMonacoEditorInstance().getSelections()) {
    const hasArgs = args && args.length;
    let text = name;

    if (!hasArgs) {
      // simple insert/replace
      this.insertAtRanges(text, ranges);
      return;
    }

    this.getMonacoEditorInstance().getModel().pushStackElement();

    const Selection = this.getMonaco().Selection;
    const nonEmptySelections = [];
    let emptySelections = [];
    ranges.forEach(range => {
      const selection = new Selection(range.startLineNumber, range.startColumn, range.endLineNumber, range.endColumn);
      if (!selection.isEmpty()) {
        nonEmptySelections.push(selection);
      } else {
        emptySelections.push(selection);
      }
    });

    if (nonEmptySelections.length) {
      const edits = [
        ...nonEmptySelections.map(sel => ({ identifier: 'dremio-inject', range: sel.collapseToStart(), text: text + '(' })),
        ...nonEmptySelections.map(sel => ({ identifier: 'dremio-inject', range: Selection.fromPositions(sel.getEndPosition()), text: ')' }))
      ];
      this.getMonacoEditorInstance().executeEdits('dremio', edits);

      // need to update emptySelections for the new insertions
      // assumes that function names are single line, and ranges don't overlap
      const nudge = text.length + 2;
      nonEmptySelections.forEach(nonEmptySel => {
        emptySelections = emptySelections.map(otherSelection => {
          let {startLineNumber, startColumn, endLineNumber, endColumn} = otherSelection;
          if (startLineNumber === nonEmptySel.endLineNumber) {
            if (startColumn >= nonEmptySel.endColumn) {
              startColumn += nudge;
              if (endLineNumber === startLineNumber) {
                endColumn += nudge;
              }
            }
          }
          return new Selection(startLineNumber, startColumn, endLineNumber, endColumn);
        });
      });
    }

    // do snippet-style insertion last so that the selection ends up with token selection
    if (emptySelections.length) {
      let i = 1; // starts with 1: https://code.visualstudio.com/docs/editor/userdefinedsnippets
      text += args.replace(/\[.*?\] /g, '').replace(/\{(.*?)\}/g, (m, p1) => `\${${i++}:${p1}}`);

      // insertSnippet only works with the current selection, so move the selection to the input range
      this.getMonacoEditorInstance().setSelections(emptySelections);
      this.sqlAutoCompleteRef.insertSnippet(text, undefined, undefined, false, false);
    }

    this.getMonacoEditorInstance().getModel().pushStackElement();
    this.focus();
  }

  insertAtRanges(text, ranges = this.getMonacoEditorInstance().getSelections()) { // getSelections() falls back to cursor location automatically
    const edits = ranges.map(range => ({ identifier: 'dremio-inject', range, text }));
    this.getMonacoEditorInstance().executeEdits('dremio', edits);
    this.getMonacoEditorInstance().pushUndoStop();
    this.focus();
  }

  updateCode() {
    if (this.props.onChange) {
      const value = this.getMonacoEditorInstance().getValue();
      this.props.onChange(value);
    }
  }

  renderSelectContextModal() {
    if (!this.state.showSelectContextModal) return null;

    const contextValue = constructFullPath(this.props.context);
    return <Modal
      isOpen
      hide={this.hideSelectContextModal}
      size='small'
      title={la('Select Context')}>
      <SelectContextForm
        onFormSubmit={this.updateContext}
        onCancel={this.hideSelectContextModal}
        initialValues={{context: contextValue}}
      />
    </Modal>;
  }

  renderContext() {
    const contextValue = this.props.context ? constructFullPath(this.props.context, true) : DEFAULT_CONTEXT;
    const showContext = this.props.pageType === 'details';
    const emptyContext = typeof contextValue === 'string' && contextValue.trim() === '';
    return (
      <div className='sqlAutocomplete__context' style={[styles.context, showContext && {display: 'none'}]}>
        {emptyContext ?
          <span className='sqlAutocomplete__contextText' onClick={this.handleClickEditContext}>Context</span> :
          <>Context:<span className='sqlAutocomplete__contextText' onClick={this.handleClickEditContext}>{contextValue}</span></>
        }
      </div>
    );
  }

  renderReferencePicker() {
    return (
      <RefPicker hide={this.props.pageType === 'details'} />
    );
  }

  handleClick() {
    localStorageUtils.setSqlThemeContrast(!this.state.isContrast);
    this.setState((state) => {
      return { isContrast: !state.isContrast };
    });
  }

  render() {
    const height = this.props.sqlSize;
    const { funcHelpPanel, isGrayed, errors, autoCompleteEnabled, context, onFunctionChange, sidebarCollapsed } = this.props;
    const { query } = this.context.location;
    const widthSqlEditor = funcHelpPanel ? styles.smallerSqlEditorWidth : sidebarCollapsed ? styles.noSidebarEditorWidth  : styles.normalSqlEditorWidth;
    return (
      <DragTarget
        dragType={this.props.dragType}
        onDrop={this.handleDrop}
        onDragOver={this.handleDragOver}
      >
        <div
          className={classNames('sqlAutocomplete', this.state.isContrast ? 'vs-dark' : 'vs')}
          name={this.props.name}
          style={[styles.base, widthSqlEditor, isGrayed && {opacity: 0.4, pointerEvents: 'none'}, this.props.style]}
        >
          <div className='sqlAutocomplete__actions'>
            {this.renderSelectContextModal()}
            { query.type !== 'transform' && this.renderContext() }
            { query.type !== 'transform' && this.renderReferencePicker() }
            <span
              data-qa='toggle-icon'
              id='toggle-icon'
              className='function__toggleIcon'
              onClick={onFunctionChange}
              style={[ this.state.isContrast ? {color: 'white'} : {color: 'black'} ]}
            >
              fn
            </span>
            <span
              data-qa='toggle-icon'
              id='toggle-icon'
              className='sql__toggleIcon'
              onClick={this.handleClick.bind(this)}
            >
              <Art
                src={this.state.isContrast ? 'sqlThemeWhite.svg' : 'sqlThemeGray.svg'}
                alt='icon'
                title={this.state.isContrast ? 'Light Mode' : 'Dark Mode'}
                style={{ width: '15px' }}
              />
            </span>
          </div>

          <SQLEditor
            height={height - 2} // .sql-autocomplete has 1px top and bottom border. Have to substract border width
            ref={(ref) => this.sqlAutoCompleteRef = ref}
            defaultValue={this.props.defaultValue}
            onChange={this.handleChange}
            errors={errors}
            autoCompleteEnabled={autoCompleteEnabled}
            sqlContext={context}
            customTheme
            theme={this.state.isContrast ? 'vs-dark' : 'vs'}
            background={this.state.isContrast ? '#333333' : '#FFFFFF'}
          />
          { this.props.children }
        </div>
      </DragTarget>
    );
  }
}

export default connect(null, null, null, { forwardRef: true })(SqlAutoComplete);

const styles = {
  base: {
    position: 'relative',
    width: '100%'
  },
  noSidebarEditorWidth: {
    width: 'calc(100vw - 16rem)'
  },
  normalSqlEditorWidth: {
    width: 'calc(100vw - 423px)'
  },
  smallerSqlEditorWidth: {
    width: '60%'
  },
  tooltip: {
    padding: '5px 10px 5px 10px',
    backgroundColor: '#f2f2f2'
  },
  messageStyle: {
    position: 'absolute',
    bottom: '0px',
    zIndex: 1000
  },
  editIcon: {
    Container: {
      height: 15,
      width: 20,
      position: 'relative',
      display: 'flex',
      alignItems: 'center',
      cursor: 'pointer'
    }
  },
  contextInput: {
    minWidth: 139,
    height: 19,
    outline: 'none',
    paddingLeft: 3
  }
};
