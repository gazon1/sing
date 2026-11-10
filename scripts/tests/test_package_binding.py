"""Tests for `scripts/package-binding.py` — the one owner of "which checkout is this?".

The packages under `scripts/src/` are installed editable, so the environment
decides which checkout's `scripts/src` answers `import sgate` — and a gate (or
a Maestro run) that measures another tree is a green run about somebody else's
code. The binding is what makes the checkout the answer instead of the
environment.

It is loaded here by path, exactly the way `./sg`, `./rm` and the gate runner
load it: the packages may be the thing that is broken, so the thing that
repairs them cannot live inside them.
"""

import importlib.util
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest

REPO = pathlib.Path(__file__).resolve().parents[2]
BINDING_PATH = REPO / "scripts" / "package-binding.py"


def _load_binding():
    """Load the binding module by path, the way every entry point reaches it."""
    spec = importlib.util.spec_from_file_location("package_binding_under_test", BINDING_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class PackageBindingTest(unittest.TestCase):
    """The binding answers for the checkout that holds it."""

    def test_it_names_every_package_of_the_install(self) -> None:
        """The four packages are siblings under `scripts/src/`, so one binding owns all."""
        binding = _load_binding()
        self.assertEqual(
            ("emulator", "scanners", "sgate", "traceability"),
            tuple(binding.PACKAGES),
            "the binding does not name every package the editable install carries",
        )

    def test_binding_puts_this_checkout_first(self) -> None:
        """The checkout's `scripts/src` goes first on `sys.path` and in `PYTHONPATH`."""
        binding = _load_binding()
        source = (REPO / "scripts" / "src").resolve()
        saved_path = list(sys.path)
        saved_pythonpath = os.environ.get("PYTHONPATH")
        try:
            bound = binding.bind_local_package(REPO)
            self.assertEqual(source, bound)
            self.assertEqual(str(source), sys.path[0])
            self.assertEqual(
                str(source),
                os.environ.get("PYTHONPATH", "").split(os.pathsep)[0],
                "the children of this process would resolve the package by themselves",
            )
        finally:
            sys.path[:] = saved_path
            if saved_pythonpath is None:
                os.environ.pop("PYTHONPATH", None)
            else:
                os.environ["PYTHONPATH"] = saved_pythonpath

    def test_binding_puts_this_checkout_first_even_when_it_is_already_on_the_path(self) -> None:
        """A path that is on `sys.path` but not first loses to a foreign checkout.

        The legacy `easy-install.pth` of an editable install puts its checkout
        at the front of `sys.path`, in front of `PYTHONPATH`. A binding that
        only adds a path that is already there binds nothing: the foreign
        checkout still answers `import sgate`.
        """
        binding = _load_binding()
        source = (REPO / "scripts" / "src").resolve()
        foreign = REPO / "scripts" / "tests"
        saved_path = list(sys.path)
        saved_pythonpath = os.environ.get("PYTHONPATH")
        try:
            sys.path[:] = [str(foreign), str(source)]
            os.environ["PYTHONPATH"] = os.pathsep.join([str(foreign), str(source)])
            binding.bind_local_package(REPO)
            self.assertEqual(
                str(source),
                sys.path[0],
                "this checkout's packages are on the path but not first — a foreign "
                "checkout's editable install still answers",
            )
            self.assertEqual(
                str(source),
                os.environ.get("PYTHONPATH", "").split(os.pathsep)[0],
                "the children of this process would resolve the package by themselves",
            )
        finally:
            sys.path[:] = saved_path
            if saved_pythonpath is None:
                os.environ.pop("PYTHONPATH", None)
            else:
                os.environ["PYTHONPATH"] = saved_pythonpath

    def test_the_packages_resolve_to_this_checkout(self) -> None:
        """Bound, every package of the install answers from this tree.

        The resolution happens in a child process: an import that already
        happened in this one would answer from `sys.modules`, and a child sees
        what a fresh `python3` sees — which is the question.
        """
        program = (
            "import importlib.util, sys\n"
            f"spec = importlib.util.spec_from_file_location('b', {str(BINDING_PATH)!r})\n"
            "binding = importlib.util.module_from_spec(spec)\n"
            "spec.loader.exec_module(binding)\n"
            "binding.bind_local_package(sys.argv[1])\n"
            "for name in binding.PACKAGES:\n"
            "    origin = binding.package_origin(name)\n"
            "    print(name, origin)\n"
        )
        result = subprocess.run(
            [sys.executable, "-c", program, str(REPO)],
            capture_output=True,
            text=True,
            timeout=120,
            stdin=subprocess.DEVNULL,
        )
        self.assertEqual(
            0,
            result.returncode,
            f"the binding did not run: {result.stderr.strip()[:400]}",
        )
        source = (REPO / "scripts" / "src").resolve()
        spoken = [line.split() for line in result.stdout.splitlines() if line.strip()]
        self.assertTrue(spoken, "the binding said nothing about the packages")
        for name, origin in spoken:
            self.assertIn(
                source,
                pathlib.Path(origin).resolve().parents,
                f"{name} resolves to {origin} — another checkout's package",
            )

    def test_it_sees_a_package_that_resolves_elsewhere(self) -> None:
        """A package outside this checkout is foreign, and is named as such."""
        binding = _load_binding()
        # A name nothing else can own: the editable finders map the four real
        # packages, not this one.
        probe = "package_binding_foreign_probe"
        with tempfile.TemporaryDirectory() as tree:
            package = pathlib.Path(tree) / probe
            package.mkdir()
            (package / "__init__.py").write_text("", encoding="utf-8")
            sys.path.insert(0, tree)
            saved_packages = binding.PACKAGES
            binding.PACKAGES = (probe,)
            try:
                origin = binding.package_origin(probe)
                self.assertIsNotNone(origin, "the probe package does not resolve")
                self.assertEqual(package.resolve(), origin)
                self.assertEqual(
                    [probe],
                    binding.foreign_packages(REPO),
                    "a package that resolves elsewhere is not reported as foreign",
                )
            finally:
                binding.PACKAGES = saved_packages
                sys.path.remove(tree)


if __name__ == "__main__":
    unittest.main()
