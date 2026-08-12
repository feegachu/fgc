(function () {
  "use strict";

  var lastFocusedElement = null;

  function getFocusable(container) {
    return Array.from(container.querySelectorAll(
      "a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), " +
      "textarea:not([disabled]), [tabindex]:not([tabindex='-1'])"
    )).filter(function (element) {
      return !element.hidden && element.getAttribute("aria-hidden") !== "true";
    });
  }

  function openModal(id) {
    var backdrop = document.querySelector("[data-modal='" + CSS.escape(id) + "']");
    if (!backdrop) return false;

    lastFocusedElement = document.activeElement;
    backdrop.hidden = false;
    backdrop.setAttribute("aria-hidden", "false");
    document.body.classList.add("is-modal-open");

    var focusable = getFocusable(backdrop);
    var initial = backdrop.querySelector("[data-modal-initial-focus]") || focusable[0];
    if (initial) initial.focus();
    return true;
  }

  function closeModal(id) {
    var backdrop = document.querySelector("[data-modal='" + CSS.escape(id) + "']");
    if (!backdrop) return false;

    backdrop.hidden = true;
    backdrop.setAttribute("aria-hidden", "true");
    document.body.classList.remove("is-modal-open");
    if (lastFocusedElement && document.contains(lastFocusedElement)) lastFocusedElement.focus();
    lastFocusedElement = null;
    return true;
  }

  document.addEventListener("click", function (event) {
    var openButton = event.target.closest("[data-modal-open]");
    if (openButton) {
      event.preventDefault();
      openModal(openButton.dataset.modalOpen);
      return;
    }

    var closeButton = event.target.closest("[data-modal-close]");
    if (closeButton) {
      event.preventDefault();
      var backdrop = closeButton.closest("[data-modal]");
      if (backdrop) closeModal(backdrop.dataset.modal);
      return;
    }

    var clickedBackdrop = event.target.matches("[data-modal][data-close-on-backdrop='true']");
    if (clickedBackdrop) closeModal(event.target.dataset.modal);
  });

  document.addEventListener("keydown", function (event) {
    var openBackdrop = document.querySelector("[data-modal]:not([hidden])");
    if (!openBackdrop) return;

    if (event.key === "Escape") {
      event.preventDefault();
      closeModal(openBackdrop.dataset.modal);
      return;
    }

    if (event.key !== "Tab") return;
    var focusable = getFocusable(openBackdrop);
    if (!focusable.length) return;
    var first = focusable[0];
    var last = focusable[focusable.length - 1];

    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  });

  window.FgcUi = window.FgcUi || {};
  window.FgcUi.modal = { open: openModal, close: closeModal };
})();
