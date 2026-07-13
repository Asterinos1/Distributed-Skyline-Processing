import pytest
from faker import Faker
import sys
import os

# Append python directory to sys.path so we can import unified_producer
sys.path.append(os.path.dirname(os.path.abspath(__file__)))
from unified_producer import generate_uniform_data, generate_correlated_data, generate_anti_correlated_data

@pytest.fixture
def faker():
    return Faker()

def test_generate_uniform_data(faker):
    dimensions = 3
    d_min = 0
    d_max = 1000
    
    data = generate_uniform_data(faker, dimensions, d_min, d_max)
    
    assert len(data) == dimensions
    for val in data:
        assert d_min <= val <= d_max
        assert isinstance(val, int)

def test_generate_correlated_data(faker):
    dimensions = 4
    d_min = 100
    d_max = 500
    
    data = generate_correlated_data(faker, dimensions, d_min, d_max, rho=0.95)
    
    assert len(data) == dimensions
    for val in data:
        assert d_min <= val <= d_max
        assert isinstance(val, int)
        
    # Check that they cluster close to each other (variance should be small for rho=0.95)
    mean_val = sum(data) / len(data)
    for val in data:
        assert abs(val - mean_val) < (d_max - d_min) * 0.15

def test_generate_anti_correlated_data(faker):
    dimensions = 3
    d_min = 0
    d_max = 1000
    
    data = generate_anti_correlated_data(faker, dimensions, d_min, d_max)
    
    assert len(data) == dimensions
    for val in data:
        assert d_min <= val <= d_max
        assert isinstance(val, int)
