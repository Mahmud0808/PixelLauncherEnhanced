(() => {
  const root = document.documentElement;
  const reduce = window.matchMedia("(prefers-reduced-motion: reduce)");
  const darkQuery = window.matchMedia("(prefers-color-scheme: dark)");
  const SURFACE = { light: "#F9F9FF", dark: "#111318" };

  const storage = {
    get(key) {
      try {
        return localStorage.getItem(key);
      } catch (e) {
        return null;
      }
    },
    set(key, value) {
      try {
        localStorage.setItem(key, value);
      } catch (e) {}
    },
  };

  const themeButton = document.querySelector("[data-theme-toggle]");
  const themeMetas = document.querySelectorAll('meta[name="theme-color"]');

  const isDark = () => (root.dataset.theme ? root.dataset.theme === "dark" : darkQuery.matches);

  const syncTheme = () => {
    const dark = isDark();
    if (themeButton) {
      themeButton.dataset.dark = String(dark);
      themeButton.setAttribute("aria-label", dark ? "Switch to light theme" : "Switch to dark theme");
    }
    if (root.dataset.theme) {
      themeMetas.forEach((meta) => meta.setAttribute("content", dark ? SURFACE.dark : SURFACE.light));
    }
  };

  if (themeButton) {
    themeButton.addEventListener("click", () => {
      const next = isDark() ? "light" : "dark";
      const apply = () => {
        root.dataset.theme = next;
        storage.set("ple-theme", next);
        syncTheme();
      };
      if (document.startViewTransition && !reduce.matches) {
        document.startViewTransition(apply);
      } else {
        apply();
      }
    });
  }
  darkQuery.addEventListener("change", syncTheme);
  syncTheme();

  const nav = document.querySelector("[data-nav]");
  let ticking = false;
  const onScroll = () => {
    if (ticking) return;
    ticking = true;
    requestAnimationFrame(() => {
      nav.classList.toggle("is-scrolled", window.scrollY > 8);
      ticking = false;
    });
  };
  window.addEventListener("scroll", onScroll, { passive: true });
  onScroll();

  const reveals = document.querySelectorAll(".reveal");
  if ("IntersectionObserver" in window && !reduce.matches) {
    const revealer = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (!entry.isIntersecting) return;
          entry.target.classList.add("is-in");
          revealer.unobserve(entry.target);
        });
      },
      { rootMargin: "0px 0px -8% 0px", threshold: 0.08 }
    );
    reveals.forEach((el) => revealer.observe(el));
  } else {
    reveals.forEach((el) => el.classList.add("is-in"));
  }

  const indexLinks = new Map();
  document.querySelectorAll(".index__link").forEach((link) => {
    indexLinks.set(link.getAttribute("href").slice(1), link);
  });
  const navLinks = new Map();
  document.querySelectorAll("[data-spy]").forEach((link) => {
    navLinks.set(link.getAttribute("href").slice(1), link);
  });

  if ("IntersectionObserver" in window) {
    const cats = document.querySelectorAll("[data-cat]");
    const catSpy = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (!entry.isIntersecting) return;
          cats.forEach((cat) => cat.classList.toggle("is-current", cat === entry.target));
          indexLinks.forEach((link, id) => {
            const active = id === entry.target.id;
            link.classList.toggle("is-active", active);
            if (active) link.setAttribute("aria-current", "true");
            else link.removeAttribute("aria-current");
          });
        });
      },
      { rootMargin: "-40% 0px -55% 0px" }
    );
    cats.forEach((cat) => catSpy.observe(cat));

    const sectionSpy = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          const link = navLinks.get(entry.target.id);
          if (!link) return;
          link.classList.toggle("is-active", entry.isIntersecting);
        });
      },
      { rootMargin: "-45% 0px -50% 0px" }
    );
    navLinks.forEach((link, id) => {
      const section = document.getElementById(id);
      if (section) sectionSpy.observe(section);
    });
  }

  const screen = document.querySelector("[data-screen]");
  if (screen) {
    screen.querySelectorAll(".app__icon").forEach((icon, i) => icon.style.setProperty("--i", i));

    const glance = screen.querySelector("[data-glance]");
    if (glance) {
      glance.textContent = new Date().toLocaleDateString("en-US", { weekday: "long", month: "short", day: "numeric" });
    }

    let touched = false;

    const select = (key, value) => {
      document.querySelectorAll(`[data-set="${key}"]`).forEach((b) => b.setAttribute("aria-pressed", String(b.dataset.value === value)));
      screen.dataset[key] = value;
    };

    document.querySelectorAll("[data-switch]").forEach((button) => {
      button.addEventListener("click", () => {
        touched = true;
        const on = button.getAttribute("aria-checked") !== "true";
        button.setAttribute("aria-checked", String(on));
        screen.dataset[button.dataset.switch] = String(on);
      });
    });

    document.querySelectorAll("[data-set]").forEach((button) => {
      button.addEventListener("click", () => {
        touched = true;
        select(button.dataset.set, button.dataset.value);
      });
    });

    const slider = document.querySelector("[data-size]");
    const sizeOut = document.querySelector("[data-size-out]");
    if (slider) {
      const update = () => {
        const value = Number(slider.value);
        const min = Number(slider.min);
        const max = Number(slider.max);
        slider.style.setProperty("--fill", `${((value - min) / (max - min)) * 100}%`);
        screen.style.setProperty("--icon-scale", String(value / 100));
        if (sizeOut) sizeOut.textContent = `${value}%`;
      };
      slider.addEventListener("input", () => {
        touched = true;
        update();
      });
      update();
    }

    if (!reduce.matches && "IntersectionObserver" in window) {
      const demo = new IntersectionObserver(
        (entries) => {
          if (!entries.some((e) => e.isIntersecting)) return;
          demo.disconnect();
          window.setTimeout(() => {
            if (!touched) select("tint", "blue");
          }, 2400);
        },
        { threshold: 0.6 }
      );
      demo.observe(screen);
    }
  }

  const release = document.querySelector("[data-release]");
  if (release && window.fetch) {
    fetch("https://api.github.com/repos/Mahmud0808/PixelLauncherEnhanced/releases/latest", {
      headers: { Accept: "application/vnd.github+json" },
    })
      .then((res) => (res.ok ? res.json() : Promise.reject(res.status)))
      .then((data) => {
        if (!data || !data.tag_name) return;
        const link = document.createElement("a");
        link.href = data.html_url;
        link.rel = "noopener";
        link.textContent = data.tag_name;
        release.append("Latest release ", link);
        if (data.published_at) {
          const date = new Date(data.published_at).toLocaleDateString("en-US", { month: "short", day: "numeric", year: "numeric" });
          release.append(` · ${date}`);
        }
        release.hidden = false;
      })
      .catch(() => {});
  }
})();
