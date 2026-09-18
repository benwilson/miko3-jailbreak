"""Robot-to-model relay for the autonomous voice mode (plan KTD1).

One asyncio process per robot, between the robot's audio link and the owner's
model server. model_client is the model server adapter.
"""
import logging

logging.getLogger("relay").addHandler(logging.NullHandler())
