"""AI support agent (Strands Agents + Anthropic). Swap the framework here without touching app.py."""

from typing import Any, Literal

from config import Config
from strands import Agent, tool
from strands.models.anthropic import AnthropicModel

MAX_TOKENS: int = 1024

# Strands rejects any other value before calling the tool.
TransferReason = Literal["customer_request", "out_of_scope", "stuck", "needs_technician"]

SYSTEM_PROMPT: str = """You are the phone support agent of Acme Fiber, an internet provider.
Everything you write is spoken to the customer by text-to-speech:
- Answer in one or two short sentences. No lists, no markdown, no emojis.
- Spell out numbers the way you would say them.

How to help:
- Ask for the account number, so the technician gets it if the call is transferred.
- Help with simple connection problems, such as restarting the router.

When to call transfer_to_human:
- reason "out_of_scope": the customer asks about cancellation, a billing dispute, a technician visit or an ownership change.
- reason "needs_technician": the problem needs a technician, such as no signal at the fiber terminal.
- reason "stuck": the conversation makes no progress, because you cannot understand the problem or the customer keeps repeating it.
- reason "customer_request": the customer asks for a person.
The summary must be two or three sentences that can be spoken in under 15 seconds:
the account number if known, the problem, and what was already tried.
After calling transfer_to_human, do not say anything else: the system tells the customer."""

SUMMARY_PROMPT: str = """The call is being transferred to a technician. Do not call any tool.
Write only the handoff summary: two or three sentences that can be spoken in under 15 seconds,
with the account number if known, the problem, and what was already tried."""


class TransferRequest:
    def __init__(self, reason: TransferReason, summary: str) -> None:
        self.reason: TransferReason = reason
        self.summary: str = summary


class SupportAgent:
    def __init__(self, config: Config) -> None:
        self.transfer_request: TransferRequest | None = None
        model: AnthropicModel = AnthropicModel(
            client_args={"api_key": config.llm_api_key},
            model_id=config.llm_model,
            max_tokens=MAX_TOKENS,
        )
        self._agent: Agent = Agent(
            model=model,
            system_prompt=SYSTEM_PROMPT,
            tools=self._create_tools(),
            callback_handler=None,
        )

    async def reply(self, text: str) -> str:
        self.transfer_request = None
        result = await self._agent.invoke_async(text)
        return str(result).strip()

    def has_conversation(self) -> bool:
        return len(self._agent.messages) > 0

    async def summarize(self) -> str:
        result = await self._agent.invoke_async(SUMMARY_PROMPT)
        return str(result).strip()

    def _create_tools(self) -> list[Any]:
        # Add your own tools here, and list them in the return value and in SYSTEM_PROMPT. For example:
        #
        # @tool
        # def lookup_account(account_number: str) -> dict[str, Any]:
        #     """Look up a customer account by account number.
        #
        #     Args:
        #         account_number: The account number given by the customer, digits only.
        #     """
        #     return your_crm.get_account(account_number)
        #
        # @tool
        # def get_ticket_status(account_number: str) -> dict[str, Any]:
        #     """Get the open support ticket of an account, if any.
        #
        #     Args:
        #         account_number: A verified account number.
        #     """
        #     return your_ticketing.get_open_ticket(account_number)

        @tool
        def transfer_to_human(reason: TransferReason, summary: str) -> str:
            """Transfer the call to a human technician.

            Args:
                reason: Why the call is transferred.
                summary: Two or three sentences for the technician: account number if known,
                    the problem, and what was already tried.
            """
            # Only records the request; the server runs the handoff.
            self.transfer_request = TransferRequest(reason, summary)
            return "Transfer requested."

        return [transfer_to_human]
