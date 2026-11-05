"""Voice Relay WebSocket messages."""

import json
from typing import Any


class RelayMessages:
    """Helpers to parse and build Voice Relay messages."""

    CONNECT: str = "connect"
    TEXT: str = "text"
    DTMF: str = "dtmf"

    @staticmethod
    def parse(raw: str) -> dict[str, Any]:
        message: Any = json.loads(raw)
        if not isinstance(message, dict) or not isinstance(message.get("command"), str):
            raise ValueError("Voice Relay message without a command")
        return message

    @staticmethod
    def answer() -> dict[str, Any]:
        return {"command": "answer"}

    @staticmethod
    def interrupt() -> dict[str, Any]:
        return {"command": "interrupt"}

    @staticmethod
    def text(text: str, is_last: bool = True, is_interruptible: bool | None = None) -> dict[str, Any]:
        message: dict[str, Any] = {"command": RelayMessages.TEXT, "text": text, "isLast": is_last}
        if is_interruptible is not None:
            message["isInterruptible"] = is_interruptible
        return message
