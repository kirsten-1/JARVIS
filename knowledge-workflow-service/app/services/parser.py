import re


_TAG_RE = re.compile(r"<[^>]+>")
_MULTI_SPACE_RE = re.compile(r"[ \t\f\v]+")
_MULTI_NEWLINE_RE = re.compile(r"\n{3,}")


def parse_content(source_type: str, content: str) -> str:
    text = content
    if source_type == "html":
        text = _TAG_RE.sub(" ", text)
    elif source_type == "markdown":
        # Keep markdown semantics simple for MVP and strip code fences markers.
        text = text.replace("```", "\n")

    text = text.replace("\r\n", "\n").replace("\r", "\n")
    text = _MULTI_SPACE_RE.sub(" ", text)
    text = _MULTI_NEWLINE_RE.sub("\n\n", text)
    return text.strip()
