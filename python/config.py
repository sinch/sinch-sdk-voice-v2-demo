"""Loads and validates environment variables, and creates the Sinch client."""

import os

from dotenv import load_dotenv
from sinch import SinchClient
from sinch.core.enums import VoiceRegionEnum

REQUIRED: list[str] = [
    "SINCH_PROJECT_ID",
    "SINCH_KEY_ID",
    "SINCH_KEY_SECRET",
    "SINCH_SERVICE_ID",
    "SINCH_NUMBER",
    "TECHNICIAN_NUMBERS",
    "RELAY_PUBLIC_URL",
    "HOLD_MUSIC_URL",
    "LLM_API_KEY",
]

# Demo settings
TTS_VOICE: str = "Emma"
STT_LANGUAGE: str = "en-US"
GREETING: str = "Welcome to Acme Fiber support. Please describe your problem, or press 1 at any time to talk to a technician."
PORT: int = 8000
DIAL_TIMEOUT_SECONDS: int = 15
LLM_MODEL: str = "claude-sonnet-5-5"

# Only needed by call_sinch_phone_number.py.
OPTIONAL: list[str] = ["TEST_FROM_NUMBER", "TEST_PHONE_NUMBER"]

# Must match the length of handoff.TECHNICIAN_CALL_NAMES.
TECHNICIANS: int = 3


class Config:
    def __init__(self, values: dict[str, str]) -> None:
        self.sinch_project_id: str = values["SINCH_PROJECT_ID"]
        self.sinch_key_id: str = values["SINCH_KEY_ID"]
        self.sinch_key_secret: str = values["SINCH_KEY_SECRET"]
        self.sinch_service_id: str = values["SINCH_SERVICE_ID"]
        self.sinch_number: str = values["SINCH_NUMBER"]
        self.technician_numbers: list[str] = [
            number.strip() for number in values["TECHNICIAN_NUMBERS"].split(",") if number.strip()
        ]
        self.dial_timeout_seconds: int = DIAL_TIMEOUT_SECONDS
        self.relay_public_url: str = values["RELAY_PUBLIC_URL"]
        self.hold_music_url: str = values["HOLD_MUSIC_URL"]
        self.tts_voice: str = TTS_VOICE
        self.stt_language: str = STT_LANGUAGE
        self.greeting: str = GREETING
        self.port: int = PORT
        self.llm_model: str = LLM_MODEL
        self.llm_api_key: str = values["LLM_API_KEY"]
        self.test_from_number: str = values["TEST_FROM_NUMBER"]
        self.test_phone_number: str = values["TEST_PHONE_NUMBER"]


def load_config() -> Config:
    load_dotenv()
    values: dict[str, str] = {}
    missing: list[str] = []
    for name in REQUIRED:
        value: str = os.environ.get(name, "").strip()
        if not value:
            missing.append(name)
        values[name] = value
    for name in OPTIONAL:
        values[name] = os.environ.get(name, "").strip()
    if missing:
        raise ValueError("Missing environment variables: " + ", ".join(missing))
    if not values["RELAY_PUBLIC_URL"].startswith(("wss://", "ws://")):
        raise ValueError("RELAY_PUBLIC_URL must start with wss:// or ws://")
    if not values["HOLD_MUSIC_URL"].startswith(("https://", "http://")):
        raise ValueError("HOLD_MUSIC_URL must start with https:// or http://")
    technicians: list[str] = [number for number in values["TECHNICIAN_NUMBERS"].split(",") if number.strip()]
    if len(technicians) != TECHNICIANS:
        raise ValueError(
            "TECHNICIAN_NUMBERS needs exactly " + str(TECHNICIANS) + " numbers (repeat a number if you have fewer)"
        )
    return Config(values)


def create_sinch_client(config: Config) -> SinchClient:
    return SinchClient(
        project_id=config.sinch_project_id,
        key_id=config.sinch_key_id,
        key_secret=config.sinch_key_secret,
        voice_region=VoiceRegionEnum.NORTH_AMERICA,
    )
