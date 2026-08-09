#!/usr/bin/env python3
"""Live AT-SPI integration test for the native Compose Linux demo."""

from __future__ import annotations

import ast
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
from typing import Callable, Sequence, TypeVar

APP_NAME = os.environ.get("KTNATIVE_ATSPI_APP_NAME", "Compose Linux · Component Catalogue")
ROOT_PATH = "/org/a11y/atspi/accessible/root"
CACHE_PATH = "/org/a11y/atspi/cache"
REGISTRY = "org.a11y.atspi.Registry"
ACCESSIBLE = "org.a11y.atspi.Accessible"
APPLICATION = "org.a11y.atspi.Application"
COMPONENT = "org.a11y.atspi.Component"
ACTION = "org.a11y.atspi.Action"
TEXT = "org.a11y.atspi.Text"
VALUE = "org.a11y.atspi.Value"
PROPERTIES = "org.freedesktop.DBus.Properties"
STATE_CHECKED = 4
STATE_SELECTED = 23
ROLE_PAGE_TAB = 37
ROLE_SLIDER = 51
ROLE_SWITCH = 130

T = TypeVar("T")


class TestFailure(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise TestFailure(message)


def wait_for(description: str, function: Callable[[], T | None], timeout: float = 20.0) -> T:
    deadline = time.monotonic() + timeout
    last_error: Exception | None = None
    while time.monotonic() < deadline:
        try:
            result = function()
            if result is not None:
                return result
        except (subprocess.SubprocessError, TestFailure, ValueError, SyntaxError) as error:
            last_error = error
        time.sleep(0.1)
    detail = f": {last_error}" if last_error else ""
    raise TestFailure(f"Timed out waiting for {description}{detail}")


def call_gdbus(
    *,
    destination: str,
    object_path: str,
    method: str,
    arguments: Sequence[str] = (),
    address: str | None = None,
    session: bool = False,
) -> str:
    command = ["gdbus", "call"]
    if session:
        command.append("--session")
    else:
        require(address is not None, "An explicit D-Bus address is required")
        command.extend(["--address", address])
    command.extend(
        [
            "--dest",
            destination,
            "--object-path",
            object_path,
            "--method",
            method,
            *arguments,
        ]
    )
    completed = subprocess.run(command, check=True, capture_output=True, text=True, timeout=5)
    return completed.stdout.strip()


def normalize_gvariant(value: str) -> str:
    value = re.sub(r"\btrue\b", "True", value)
    value = re.sub(r"\bfalse\b", "False", value)
    value = re.sub(r"\bobjectpath\s+('(?:[^'\\]|\\.)*')", r"\1", value)
    value = re.sub(r"\b(?:byte|int16|uint16|int32|uint32|int64|uint64)\s+(-?\d+)", r"\1", value)
    value = re.sub(
        r"\bdouble\s+([-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?)",
        r"\1",
        value,
    )
    return value


def parse_gvariant(value: str):
    return ast.literal_eval(normalize_gvariant(value))


def get_bus_address() -> str:
    result = call_gdbus(
        destination="org.a11y.Bus",
        object_path="/org/a11y/bus",
        method="org.a11y.Bus.GetAddress",
        session=True,
    )
    address = parse_gvariant(result)[0]
    require(isinstance(address, str) and address, "org.a11y.Bus returned an empty address")
    return address


def registry_children(address: str) -> list[tuple[str, str]]:
    result = call_gdbus(
        address=address,
        destination=REGISTRY,
        object_path=ROOT_PATH,
        method=f"{ACCESSIBLE}.GetChildren",
    )
    return list(parse_gvariant(result)[0])


def get_property(address: str, destination: str, path: str, interface: str, property_name: str) -> str:
    return call_gdbus(
        address=address,
        destination=destination,
        object_path=path,
        method=f"{PROPERTIES}.Get",
        arguments=(interface, property_name),
    )


def get_name(address: str, destination: str, path: str = ROOT_PATH) -> str:
    return get_property(address, destination, path, ACCESSIBLE, "Name")


def find_application(address: str) -> str | None:
    for bus_name, path in registry_children(address):
        try:
            if APP_NAME in get_name(address, bus_name, path):
                return bus_name
        except subprocess.SubprocessError:
            continue
    return None


def get_interfaces(address: str, destination: str, path: str) -> list[str]:
    result = call_gdbus(
        address=address,
        destination=destination,
        object_path=path,
        method=f"{ACCESSIBLE}.GetInterfaces",
    )
    return list(parse_gvariant(result)[0])


def get_role(address: str, destination: str, path: str) -> int:
    result = call_gdbus(
        address=address,
        destination=destination,
        object_path=path,
        method=f"{ACCESSIBLE}.GetRole",
    )
    return int(parse_gvariant(result)[0])


def get_cache(address: str, destination: str, output_file: Path | None = None) -> list[tuple]:
    result = call_gdbus(
        address=address,
        destination=destination,
        object_path=CACHE_PATH,
        method="org.a11y.atspi.Cache.GetItems",
    )
    if output_file is not None:
        output_file.write_text(result + "\n", encoding="utf-8")
    return list(parse_gvariant(result)[0])


def item_path(item: tuple) -> str:
    return item[0][1]


def item_interfaces(item: tuple) -> list[str]:
    return list(item[5])


def item_name(item: tuple) -> str:
    return item[6]


def item_role(item: tuple) -> int:
    return int(item[7])


def item_states(item: tuple) -> list[int]:
    return list(item[9])


def has_state(item: tuple, state: int) -> bool:
    states = item_states(item)
    word = state // 32
    return word < len(states) and bool(states[word] & (1 << (state % 32)))


def find_item(items: list[tuple], predicate: Callable[[tuple], bool]) -> tuple | None:
    return next((item for item in items if predicate(item)), None)


def parse_variant_double(value: str) -> float:
    match = re.search(r"<\s*([-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?)\s*>", value)
    require(match is not None, f"Could not parse a D-Bus double from {value!r}")
    return float(match.group(1))


def stop_process(process: subprocess.Popen, timeout: float = 5.0) -> None:
    if process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=timeout)
        raise TestFailure(f"Process {process.pid} did not stop after SIGTERM")


def main() -> int:
    script_root = Path(__file__).resolve().parent
    app_root = script_root.parent
    binary = Path(os.environ.get("KTNATIVE_ATSPI_BINARY", app_root / "build/bin/compose-wayland"))
    output_root = Path(os.environ.get("KTNATIVE_ATSPI_TEST_OUTPUT", app_root / "build/atspi-test"))
    output_root.mkdir(parents=True, exist_ok=True)
    app_log_path = output_root / "app.log"
    event_log_path = output_root / "events.log"
    initial_cache_path = output_root / "cache-initial.txt"
    controls_cache_path = output_root / "cache-controls.txt"
    final_cache_path = output_root / "cache-final.txt"

    require(binary.is_file() and os.access(binary, os.X_OK), f"Executable not found: {binary}")
    for command in ("gdbus", "dbus-monitor"):
        require(shutil.which(command) is not None, f"Required command is unavailable: {command}")

    address = get_bus_address()
    environment = os.environ.copy()
    environment.setdefault("KTNATIVE_ATSPI_DEBUG", "1")

    app_log = app_log_path.open("w", encoding="utf-8")
    event_log = event_log_path.open("w", encoding="utf-8")
    app = subprocess.Popen(
        [str(binary)],
        cwd=app_root,
        env=environment,
        stdout=app_log,
        stderr=subprocess.STDOUT,
        text=True,
    )
    monitor: subprocess.Popen | None = None
    app_bus = ""

    try:
        def discover() -> str | None:
            if app.poll() is not None:
                raise TestFailure(f"Application exited before AT-SPI registration with status {app.returncode}")
            return find_application(address)

        app_bus = wait_for("Compose application registration", discover)
        print(f"AT-SPI bus: {address}")
        print(f"Application bus name: {app_bus}")

        monitor = subprocess.Popen(
            [
                "dbus-monitor",
                "--address",
                address,
                f"type='signal',sender='{app_bus}',interface='org.a11y.atspi.Event.Object'",
            ],
            stdout=event_log,
            stderr=subprocess.STDOUT,
            text=True,
        )
        time.sleep(0.1)

        root_interfaces = get_interfaces(address, app_bus, ROOT_PATH)
        require(root_interfaces == [ACCESSIBLE, APPLICATION], f"Unexpected application interfaces: {root_interfaces}")
        require(get_role(address, app_bus, ROOT_PATH) == 75, "Application role is not 75")
        parent = get_property(address, app_bus, ROOT_PATH, ACCESSIBLE, "Parent")
        require(REGISTRY in parent and ROOT_PATH in parent, f"Unexpected application parent: {parent}")

        children = call_gdbus(
            address=address,
            destination=app_bus,
            object_path=ROOT_PATH,
            method=f"{ACCESSIBLE}.GetChildren",
        )
        child_refs = list(parse_gvariant(children)[0])
        require(child_refs, "Application root has no SDL window child")
        window_bus, window_path = child_refs[0]
        require(window_bus == app_bus, f"Window belongs to an unexpected bus name: {window_bus}")
        window_interfaces = get_interfaces(address, app_bus, window_path)
        require(window_interfaces == [ACCESSIBLE, COMPONENT], f"Unexpected window interfaces: {window_interfaces}")
        require(get_role(address, app_bus, window_path) == 23, "Window role is not 23")

        extents_output = call_gdbus(
            address=address,
            destination=app_bus,
            object_path=window_path,
            method=f"{COMPONENT}.GetExtents",
            arguments=("uint32 1",),
        )
        parsed_extents = parse_gvariant(extents_output)
        extents_value = parsed_extents[0] if len(parsed_extents) == 1 else parsed_extents
        extents = tuple(int(value) for value in extents_value)
        require(len(extents) == 4 and extents[2] > 0 and extents[3] > 0, f"Invalid window extents: {extents}")

        introspection_output = call_gdbus(
            address=address,
            destination=app_bus,
            object_path=ROOT_PATH,
            method="org.freedesktop.DBus.Introspectable.Introspect",
        )
        introspection = parse_gvariant(introspection_output)[0]
        require('name="GetExtents"' in introspection and 'type="(iiii)"' in introspection, "GetExtents introspection is not a rectangle struct")
        require('name="GetCharacterAtOffset"' in introspection and 'type="i" name="character"' in introspection, "Text character introspection is not int32")
        require('name="GetItems"' in introspection and 'a((so)(so)(so)iiassusau)' in introspection, "Cache introspection does not match the exported cache ABI")

        initial_items = get_cache(address, app_bus, initial_cache_path)
        require(initial_items, "AT-SPI cache is empty")
        for item in initial_items:
            interfaces = item_interfaces(item)
            require(interfaces, f"Cache item has no interfaces: {item_path(item)}")
            require(
                all(interface.startswith("org.a11y.atspi.") for interface in interfaces),
                f"Cache item uses a short interface name: {item_path(item)} {interfaces}",
            )

        root_item = find_item(initial_items, lambda item: item_path(item) == ROOT_PATH)
        require(root_item is not None, "Application root is absent from Cache.GetItems")
        require(root_item[2] == (REGISTRY, ROOT_PATH), f"Unexpected cached application parent: {root_item[2]}")

        buttons = find_item(
            initial_items,
            lambda item: item_name(item) == "Buttons"
            and item_role(item) == ROLE_PAGE_TAB
            and ACTION in item_interfaces(item),
        )
        require(buttons is not None, "Buttons page tab was not found dynamically in the cache")
        buttons_path = item_path(buttons)
        require(not has_state(buttons, STATE_SELECTED), "Buttons page tab was already selected")

        text_output = call_gdbus(
            address=address,
            destination=app_bus,
            object_path=buttons_path,
            method=f"{TEXT}.GetText",
            arguments=("int32 0", "int32 -1"),
        )
        require(parse_gvariant(text_output)[0] == "Buttons", f"Unexpected Buttons text: {text_output}")
        character_output = call_gdbus(
            address=address,
            destination=app_bus,
            object_path=buttons_path,
            method=f"{TEXT}.GetCharacterAtOffset",
            arguments=("int32 0",),
        )
        require(parse_gvariant(character_output)[0] == ord("B"), f"Unexpected first Buttons character: {character_output}")

        action_output = call_gdbus(
            address=address,
            destination=app_bus,
            object_path=buttons_path,
            method=f"{ACTION}.DoAction",
            arguments=("int32 0",),
        )
        require(parse_gvariant(action_output)[0] is True, f"Buttons DoAction failed: {action_output}")

        def controls_ready() -> list[tuple] | None:
            items = get_cache(address, app_bus)
            current_buttons = find_item(items, lambda item: item_path(item) == buttons_path)
            slider = find_item(items, lambda item: item_role(item) == ROLE_SLIDER and VALUE in item_interfaces(item))
            if current_buttons is not None and has_state(current_buttons, STATE_SELECTED) and slider is not None:
                return items
            return None

        controls_items = wait_for("Buttons state and controls subtree", controls_ready)
        controls_cache_path.write_text(
            call_gdbus(
                address=address,
                destination=app_bus,
                object_path=CACHE_PATH,
                method="org.a11y.atspi.Cache.GetItems",
            )
            + "\n",
            encoding="utf-8",
        )
        require(len(controls_items) > len(initial_items), "Controls page did not increase the cache node count")

        slider = find_item(
            controls_items,
            lambda item: item_role(item) == ROLE_SLIDER and VALUE in item_interfaces(item),
        )
        require(slider is not None, "Slider was not found dynamically after opening Buttons")
        slider_path = item_path(slider)
        current_before = parse_variant_double(get_property(address, app_bus, slider_path, VALUE, "CurrentValue"))

        set_output = call_gdbus(
            address=address,
            destination=app_bus,
            object_path=slider_path,
            method=f"{PROPERTIES}.Set",
            arguments=(VALUE, "CurrentValue", "<75.0>"),
        )
        require(set_output == "()", f"Slider property assignment failed: {set_output}")

        def slider_updated() -> list[tuple] | None:
            current_output = get_property(address, app_bus, slider_path, VALUE, "CurrentValue")
            current = parse_variant_double(current_output)
            cache_output = call_gdbus(
                address=address,
                destination=app_bus,
                object_path=CACHE_PATH,
                method="org.a11y.atspi.Cache.GetItems",
            )
            final_cache_path.write_text(cache_output + "\n", encoding="utf-8")
            items = list(parse_gvariant(cache_output)[0])
            label = find_item(items, lambda item: item_name(item).startswith("Value 75"))
            if abs(current - 75.0) <= 0.5 and label is not None:
                return items
            return None

        final_items = wait_for("slider value and Compose label update", slider_updated)
        final_cache_path.write_text(
            call_gdbus(
                address=address,
                destination=app_bus,
                object_path=CACHE_PATH,
                method="org.a11y.atspi.Cache.GetItems",
            )
            + "\n",
            encoding="utf-8",
        )
        current_after = parse_variant_double(get_property(address, app_bus, slider_path, VALUE, "CurrentValue"))

        toggle = find_item(
            final_items,
            lambda item: item_role(item) == ROLE_SWITCH and ACTION in item_interfaces(item),
        )
        require(toggle is not None, "No switch action was found on the controls page")
        toggle_path = item_path(toggle)
        checked_before = has_state(toggle, STATE_CHECKED)
        toggle_output = call_gdbus(
            address=address,
            destination=app_bus,
            object_path=toggle_path,
            method=f"{ACTION}.DoAction",
            arguments=("int32 0",),
        )
        require(parse_gvariant(toggle_output)[0] is True, f"Switch DoAction failed: {toggle_output}")

        def toggle_updated() -> bool | None:
            items = get_cache(address, app_bus)
            current = find_item(items, lambda item: item_path(item) == toggle_path)
            if current is not None and has_state(current, STATE_CHECKED) != checked_before:
                return True
            return None

        wait_for("switch checked-state mutation", toggle_updated)
        time.sleep(0.3)

        if monitor is not None:
            stop_process(monitor)
            monitor = None
        event_log.flush()
        events = event_log_path.read_text(encoding="utf-8", errors="replace")
        require("member=PropertyChange" in events, "No AT-SPI property-change event was captured")
        require('string "accessible-value"' in events, "No slider accessible-value event was captured")
        require("member=StateChanged" in events, "No AT-SPI state-change event was captured")

        print(f"Application/window hierarchy passed; extents={extents}")
        print(f"Cache interfaces passed; nodes {len(initial_items)} -> {len(controls_items)}")
        print(f"Buttons DoAction passed; selected state changed on {buttons_path}")
        print(f"Slider value passed; {current_before:g} -> {current_after:g} on {slider_path}")
        print(f"Switch DoAction passed; checked state changed on {toggle_path}")
        print("Incremental property and state events passed")
    except Exception:
        app_log.flush()
        event_log.flush()
        print(f"Application log: {app_log_path}", file=sys.stderr)
        print(f"Event log: {event_log_path}", file=sys.stderr)
        raise
    finally:
        if monitor is not None:
            try:
                stop_process(monitor)
            except TestFailure:
                pass
        stop_process(app)
        app_log.close()
        event_log.close()

    def removed() -> bool | None:
        return True if all(bus_name != app_bus for bus_name, _ in registry_children(address)) else None

    wait_for("registry cleanup after application exit", removed, timeout=5.0)
    print("Registry cleanup passed; application process terminated")
    print("AT-SPI integration test passed")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (TestFailure, subprocess.SubprocessError) as error:
        print(f"AT-SPI integration test failed: {error}", file=sys.stderr)
        raise SystemExit(1)
