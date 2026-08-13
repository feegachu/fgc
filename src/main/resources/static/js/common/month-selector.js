(function () {
  "use strict";

  var MONTH_PATTERN = /^\d{4}-(0[1-9]|1[0-2])$/;
  var KEYBOARD_OFFSETS = {
    ArrowLeft: -1,
    ArrowRight: 1,
    ArrowUp: -4,
    ArrowDown: 4
  };

  function actualCurrentMonth() {
    var now = new Date();
    return String(now.getFullYear()).padStart(4, "0") + "-" + String(now.getMonth() + 1).padStart(2, "0");
  }

  function parseDisabledMonths(root) {
    try {
      var parsed = JSON.parse(root.dataset.disabledMonths || "[]");
      return Array.isArray(parsed) ? parsed.filter(function (month) { return MONTH_PATTERN.test(month); }) : [];
    } catch (error) {
      return [];
    }
  }

  function toMonthValue(year, monthIndex) {
    return String(year).padStart(4, "0") + "-" + String(monthIndex).padStart(2, "0");
  }

  function formatMonth(month) {
    var parts = month.split("-");
    return Number(parts[0]) + "년 " + Number(parts[1]) + "월";
  }

  function createMonthSelector(root, options) {
    var settings = options || {};
    var trigger = root.querySelector("[data-action='toggle-month-selector']");
    var popover = root.querySelector(".month-selector-popover");
    var grid = root.querySelector("[data-month-selector-grid]");
    var cells = Array.prototype.slice.call(root.querySelectorAll("[data-month-index]"));
    var yearLabel = root.querySelector("[data-month-selector-year]");
    var triggerValue = root.querySelector("[data-month-selector-value]");
    var draftValue = root.querySelector("[data-month-selector-draft-value]");
    var scopeValue = root.querySelector("[data-month-selector-scope-value]");
    var tabCount = root.querySelector("[data-month-selector-tab-count]");
    var applyButton = root.querySelector("[data-action='apply-month-selector']");
    var applyLabel = root.querySelector("[data-month-selector-apply-label]");
    var loadingIcon = root.querySelector(".month-selector-loading");
    var errorRegion = root.querySelector("[data-month-selector-error]");
    var disabledMonths = parseDisabledMonths(root);
    var state = {
      value: MONTH_PATTERN.test(root.dataset.value || "") ? root.dataset.value : actualCurrentMonth(),
      draftValue: null,
      currentMonth: actualCurrentMonth(),
      displayYear: 0,
      open: false,
      applying: false
    };

    if (!trigger || !popover || !grid || !cells.length || !applyButton) return null;

    function isDisabled(month) {
      return disabledMonths.indexOf(month) !== -1;
    }

    function updateCell(cell) {
      var month = toMonthValue(state.displayYear, Number(cell.dataset.monthIndex));
      var selected = month === state.draftValue;
      var current = month === state.currentMonth;
      var disabled = isDisabled(month);
      var stateText = cell.querySelector(".month-selector-cell-state");

      cell.dataset.month = month;
      cell.disabled = disabled || state.applying;
      cell.classList.toggle("is-selected", selected);
      cell.classList.toggle("is-current", current);
      cell.classList.toggle("is-disabled", disabled);
      cell.setAttribute("aria-selected", String(selected));
      cell.setAttribute("aria-current", current ? "date" : "false");
      cell.setAttribute("aria-label", Number(cell.dataset.monthIndex) + "월" +
        (selected ? ", 선택됨" : "") + (current ? ", 현재" : "") + (disabled ? ", 선택 불가" : ""));

      if (stateText) {
        stateText.textContent = selected && current ? "선택됨 · 현재" : selected ? "선택됨" : current ? "현재" : "";
        stateText.hidden = !selected && !current;
      }
    }

    function render() {
      yearLabel.textContent = state.displayYear + "년";
      grid.setAttribute("aria-label", state.displayYear + "년 정산월 선택");
      cells.forEach(updateCell);
      triggerValue.textContent = state.value;
      if (state.draftValue) {
        draftValue.textContent = formatMonth(state.draftValue);
        scopeValue.textContent = formatMonth(state.draftValue);
      }
      tabCount.textContent = String(typeof settings.getOpenTabCount === "function" ? settings.getOpenTabCount() : 1);
      applyButton.disabled = state.applying || !state.draftValue || state.draftValue === state.value || isDisabled(state.draftValue);
      applyButton.classList.toggle("is-loading", state.applying);
      applyButton.setAttribute("aria-busy", String(state.applying));
      applyLabel.textContent = state.applying ? "적용 중" : "적용";
      loadingIcon.hidden = !state.applying;
    }

    function focusInitialCell() {
      var selected = cells.find(function (cell) { return cell.dataset.month === state.draftValue && !cell.disabled; });
      var current = cells.find(function (cell) { return cell.dataset.month === state.currentMonth && !cell.disabled; });
      var firstEnabled = cells.find(function (cell) { return !cell.disabled; });
      (selected || current || firstEnabled || trigger).focus();
    }

    function open() {
      if (state.applying) return;
      state.draftValue = state.value;
      state.displayYear = Number(state.value.slice(0, 4));
      state.open = true;
      errorRegion.textContent = "";
      popover.hidden = false;
      root.classList.add("is-open");
      trigger.setAttribute("aria-expanded", "true");
      render();
      window.requestAnimationFrame(focusInitialCell);
    }

    function close(restoreFocus) {
      if (!state.open || state.applying) return;
      state.draftValue = null;
      state.open = false;
      popover.hidden = true;
      root.classList.remove("is-open");
      trigger.setAttribute("aria-expanded", "false");
      if (restoreFocus !== false) trigger.focus();
    }

    function changeYear(offset) {
      if (state.applying) return;
      state.displayYear += offset;
      render();
      var focusTarget = cells.find(function (cell) {
        return Number(cell.dataset.monthIndex) === Number((state.draftValue || state.currentMonth).slice(5, 7)) && !cell.disabled;
      });
      if (focusTarget) focusTarget.focus();
      if (typeof settings.onYearChange === "function") settings.onYearChange(state.displayYear);
    }

    function selectCell(cell) {
      if (!cell || cell.disabled || state.applying) return;
      state.draftValue = cell.dataset.month;
      render();
    }

    function setApplying(applying) {
      state.applying = applying;
      trigger.disabled = applying;
      root.querySelector("[data-action='previous-month-selector-year']").disabled = applying;
      root.querySelector("[data-action='next-month-selector-year']").disabled = applying;
      root.querySelector("[data-action='cancel-month-selector']").disabled = applying;
      render();
    }

    function showApplyError(error) {
      setApplying(false);
      var message = error && error.message ? error.message : "기준 정산월을 적용하지 못했습니다.";
      errorRegion.textContent = message;
      if (window.FgcUi && typeof window.FgcUi.toast === "function") window.FgcUi.toast(message, "error");
    }

    function commitApply(nextValue) {
      state.value = nextValue;
      root.dataset.value = nextValue;
      setApplying(false);
      close(true);
    }

    function apply() {
      if (applyButton.disabled || !state.draftValue) return;
      var nextValue = state.draftValue;
      setApplying(true);
      try {
        var result = typeof settings.onApply === "function" ? settings.onApply(nextValue, state.value) : null;
        if (result && typeof result.then === "function") {
          result.then(function () { commitApply(nextValue); }).catch(showApplyError);
        } else {
          commitApply(nextValue);
        }
      } catch (error) {
        showApplyError(error);
      }
    }

    function moveCellFocus(currentCell, offset) {
      var start = cells.indexOf(currentCell);
      if (start < 0) return;
      var target = start + offset;
      while (target >= 0 && target < cells.length && cells[target].disabled) target += offset < 0 ? -1 : 1;
      if (target >= 0 && target < cells.length) cells[target].focus();
    }

    trigger.addEventListener("click", function () {
      if (state.open) close(true);
      else open();
    });

    root.querySelector("[data-action='previous-month-selector-year']").addEventListener("click", function () { changeYear(-1); });
    root.querySelector("[data-action='next-month-selector-year']").addEventListener("click", function () { changeYear(1); });
    root.querySelector("[data-action='cancel-month-selector']").addEventListener("click", function () { close(true); });
    applyButton.addEventListener("click", apply);

    grid.addEventListener("click", function (event) {
      selectCell(event.target.closest("[data-month-index]"));
    });

    grid.addEventListener("keydown", function (event) {
      if (event.key === "Enter" || event.key === " " || event.key === "Spacebar") {
        event.preventDefault();
        selectCell(event.target.closest("[data-month-index]"));
        return;
      }
      if (!Object.prototype.hasOwnProperty.call(KEYBOARD_OFFSETS, event.key)) return;
      event.preventDefault();
      moveCellFocus(event.target.closest("[data-month-index]"), KEYBOARD_OFFSETS[event.key]);
    });

    document.addEventListener("pointerdown", function (event) {
      if (state.open && !root.contains(event.target)) close(true);
    });

    document.addEventListener("keydown", function (event) {
      if (event.key === "Escape" && state.open) {
        event.preventDefault();
        close(true);
      }
    });

    render();
    return {
      close: close,
      open: open,
      getState: function () { return Object.assign({}, state); }
    };
  }

  window.FgcUi = window.FgcUi || {};
  window.FgcUi.createMonthSelector = createMonthSelector;
})();
