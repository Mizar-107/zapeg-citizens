"""Process entry point for ``python -m citizen_brain``."""

from __future__ import annotations

import logging

from .config import Settings
from .http_api import BrainApplication, create_server
from .job_service import JobService
from .provider import OllamaChatProvider
from .service import BrainService
from .storage import SQLiteStore


def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    try:
        settings = Settings.from_env()
        store = SQLiteStore(settings.db_path)
    except (ValueError, OSError) as exc:
        raise SystemExit(f"configuration error: {exc}") from exc

    provider = OllamaChatProvider(
        url=settings.llm_url,
        model=settings.llm_model,
        api_key=settings.llm_api_key,
        timeout_seconds=settings.llm_timeout_seconds,
        max_response_bytes=settings.max_provider_bytes,
        queue_timeout_seconds=settings.llm_queue_timeout_seconds,
        concurrency=settings.llm_concurrency,
    )
    service = BrainService(settings=settings, store=store, provider=provider)
    job_service = JobService(settings=settings, store=store, provider=provider)
    application = BrainApplication(
        service=service,
        job_service=job_service,
        bearer_token=settings.brain_token,
        max_body_bytes=settings.max_body_bytes,
    )
    server = create_server((settings.bind, settings.port), application)
    logging.getLogger("citizen_brain").info(
        "shared brain listening on %s:%d with model %s",
        settings.bind,
        settings.port,
        settings.llm_model,
    )
    try:
        server.serve_forever(poll_interval=0.5)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
