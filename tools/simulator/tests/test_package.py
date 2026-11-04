import stayops_simulator


def test_describe_marks_output_as_synthetic() -> None:
    assert "synthetic" in stayops_simulator.describe()


def test_main_prints_description(capsys) -> None:
    stayops_simulator.main()
    assert capsys.readouterr().out.strip() == stayops_simulator.describe()
