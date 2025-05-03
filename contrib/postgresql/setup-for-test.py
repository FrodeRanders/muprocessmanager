#!/usr/bin/env python3

import subprocess
import time
import pexpect
import sys

import os

def pull_postgres_image():
    """
    Pull the latest postgres image from Docker Hub.
    """
    print("Pulling the latest 'postgres' image from Docker Hub...")
    subprocess.run(["docker", "pull", "postgres"], check=True)
    print("Image pulled successfully.\n")


def remove_existing_container(container_name: str):
    """
    If a container with the given name exists, remove it.
    """
    print(f"Removing existing container named '{container_name}' (if any)...")
    result = subprocess.run(["docker", "rm", "-f", container_name], capture_output=True, text=True)
    if result.returncode == 0:
        print(f"Removed old container '{container_name}'.\n")
    else:
        print(f"No existing container '{container_name}' to remove (or removal failed). Proceeding.\n")


def start_postgres_container(container_name: str, postgres_password: str, host_port: int = 1402):
    """
    Start a new postgres container with the specified name, password, and mapped port.
    """
    # The directory in which *this* script is located:
    script_dir = os.path.dirname(os.path.abspath(__file__))

    print(f"Starting new container '{container_name}' on port {host_port} ...")
    subprocess.run([
        "docker", "run",
        "--name", container_name,
        "-e", f"POSTGRES_PASSWORD={postgres_password}",
        "-p", f"{host_port}:5432",
        "-v", f"{script_dir}:/tmp",
        "-d",
        "postgres"
    ], check=True)
    print("Container started.\n")


def wait_for_container_startup(wait_seconds: int = 5):
    """
    Give the container some time to finish startup procedures.
    """
    print(f"Waiting {wait_seconds} seconds for the database to initialize...")
    time.sleep(wait_seconds)
    print("Proceeding...\n")


def run_psql_commands_in_container(
        container_name: str,
        postgres_password: str,
        commands: list,
        psql_user: str = "postgres",
        psql_host: str = "localhost"
):
    """
    Spawns a docker run psql session (interactive) and executes SQL commands using pexpect.
    """
    print("Entering container to run psql commands...")
    shell_cmd = f"docker exec -it {container_name} psql -h {psql_host} -U {psql_user}"

    # Spawn the psql session
    child = pexpect.spawn(shell_cmd, encoding="utf-8", timeout=5)
    # child.logfile = sys.stdout

    child.expect("postgres=#", timeout=5)

    # Execute each command
    for cmd in commands:
        child.sendline(cmd)
        # Wait for psql prompt or an ERROR
        # Some psql prompts end in "postgres=#" or similar.
        idx = child.expect(["postgres=#", "ERROR", pexpect.TIMEOUT, pexpect.EOF], timeout=5)
        if idx == 1:
            # If we matched "ERROR"
            print(f"Error running command: {cmd}\nDetail: {child.before}")
        elif idx in (2, 3):
            print(f"Unexpected response or no response for command: {cmd}")

    # Exit from psql
    child.sendline("\\q")
    child.close()
    print("Commands executed. Exiting psql.\n")

def run_sql_commands_in_container(container_name, db_user, sql_files, psql_host="localhost"):
    """
    Connects to psql inside the running container with pexpect,
    then executes each .sql file via \\i /path/to/file.sql.
    """
    shell_cmd = f"docker exec -it {container_name} psql -h {psql_host} -U {db_user}"

    child = pexpect.spawn(shell_cmd, encoding="utf-8", timeout=10)
    # child.logfile = sys.stdout

    child.expect(db_user + "=>", timeout=5)

    # For each SQL file, run the psql command "\i <file>"
    for sql_file in sql_files:
        cmd = f"\\i /tmp/{sql_file}"
        child.sendline(cmd)
        idx = child.expect([db_user + "=>", "ERROR", pexpect.TIMEOUT, pexpect.EOF], timeout=10)
        if idx == 1:
            print(f"Error running command: {cmd}\nDetail: {child.before}")
        elif idx in (2, 3):
            print(f"Unexpected response or no response for command: {cmd}")

    # Exit psql
    child.sendline("\\q")
    child.close()
    print("All SQL files have been executed.\n")


def main():
    """
    Orchestrates pulling the image, removing old container, starting a new one,
    and running psql commands.
    """
    container_name = "muproc"
    postgres_password = "H0nd@666"
    host_port = 1402

    db_user = "muproc"
    db_password = "muproc"

    # Pull the postgres image
    pull_postgres_image()

    # Remove any existing container
    remove_existing_container(container_name)

    # Start the container
    start_postgres_container(container_name, postgres_password, host_port)

    # Wait for container to become ready
    wait_for_container_startup(wait_seconds=10)

    # Prepare our commands
    commands = [
        "CREATE USER " + db_user + " WITH PASSWORD '" + db_password + "';",
        "CREATE DATABASE " + container_name + ";",
        "ALTER DATABASE " + container_name + " OWNER TO " + db_user + ";"
    ]

    # Run psql commands in the container
    run_psql_commands_in_container(
        container_name=container_name,
        postgres_password=postgres_password,
        commands=commands
    )

    sql_files = ["database-create.sql"]
    run_sql_commands_in_container(
            container_name=container_name,
            db_user=db_user,
            sql_files=sql_files,
    )

    print("Setup completed.\n")
    print("You can now connect using something like:")
    print(f"  psql -h localhost -p {host_port} -U postgres")
    print("using the superuser password you specified.\n")


if __name__ == "__main__":
    main()
