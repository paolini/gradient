from decimal import Decimal

import pytest

from gradient.cells import CellError, GradeScale, format_grade, grade_violation, parse_flag, parse_grade, parse_text

ALL = GradeScale(modifiers=["+", "-", "L"])


@pytest.mark.parametrize("value, expected", [
    ("7", (Decimal("7"), "")),
    ("7,5", (Decimal("7.5"), "")),
    (" 7.25 ", (Decimal("7.25"), "")),
    ("7+", (Decimal("7"), "+")),
    ("8-", (Decimal("8"), "-")),
    ("30L", (Decimal("30"), "L")),
    ("30l", (Decimal("30"), "L")),
    ("30 e lode", (Decimal("30"), "L")),
    ("30 E LODE", (Decimal("30"), "L")),
    ("0", (Decimal("0"), "")),
    ("", None),
    ("   ", None),
])
def test_parse_grade(value, expected):
    assert parse_grade(value, ALL) == expected


@pytest.mark.parametrize("value", ["7,5+", "7.5L", "abc", "7++", "30L+", "7,", ",5", "1,23456", "1000000"])
def test_rejected_grades(value):
    with pytest.raises(CellError):
        parse_grade(value, ALL)


def test_missing_is_not_zero():
    assert parse_grade("", ALL) is None
    assert parse_grade("0", ALL) == (Decimal("0"), "")


def test_modifiers_follow_column_configuration():
    with pytest.raises(CellError, match="non è ammesso"):
        parse_grade("7+", GradeScale())
    assert parse_grade("7+", GradeScale(modifiers=["+"])) == (Decimal("7"), "+")
    with pytest.raises(CellError):
        parse_grade("30L", GradeScale(modifiers=["+", "-"]))


def test_scale_limits():
    scale = GradeScale(maximum=Decimal("10"))
    assert parse_grade("10", scale)
    with pytest.raises(CellError, match="massimo"):
        parse_grade("10,5", scale)
    with pytest.raises(CellError, match="negativi"):
        parse_grade("-1", scale)
    relaxed = GradeScale(maximum=Decimal("10"), allow_above_maximum=True, allow_negative=True)
    assert parse_grade("11", relaxed) == (Decimal("11"), "")
    assert parse_grade("-0,25", relaxed) == (Decimal("-0.25"), "")


def test_negative_with_modifier_is_distinct_from_minus_modifier():
    scale = GradeScale(allow_negative=True, modifiers=["-"])
    assert parse_grade("-2", scale) == (Decimal("-2"), "")
    assert parse_grade("2-", scale) == (Decimal("2"), "-")


def test_format_grade_keeps_modifier_and_drops_trailing_zeros():
    assert format_grade(Decimal("7.5000"), "") == "7,5"
    assert format_grade(Decimal("30.0000"), "L") == "30L"
    assert format_grade(Decimal("100"), "") == "100"


def test_grade_violation_for_existing_values():
    assert grade_violation(Decimal("7"), "+", GradeScale(modifiers=["+"])) is None
    assert grade_violation(Decimal("7"), "+", GradeScale()) is not None
    assert grade_violation(Decimal("12"), "", GradeScale(maximum=Decimal("10"))) is not None


@pytest.mark.parametrize("value, expected", [
    ("sì", True), ("SI", True), ("s", True), ("x", True), ("1", True),
    ("no", False), ("N", False), ("0", False), ("", None), ("  ", None),
])
def test_parse_flag_three_states(value, expected):
    assert parse_flag(value) is expected


def test_two_state_flags_treat_empty_as_off():
    assert parse_flag("", allow_empty=False) is False
    with pytest.raises(CellError):
        parse_flag("forse")


def test_text_blank_is_missing():
    assert parse_text("   ") is None
    assert parse_text("  nota ") == "nota"
    with pytest.raises(CellError):
        parse_text("x" * 1001)
