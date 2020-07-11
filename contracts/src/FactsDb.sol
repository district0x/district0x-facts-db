pragma solidity ^0.4.24;

contract FactsDb {
  event Fact(uint entity, string attribute, string val,  bool add);
  event Fact(uint entity, string attribute, uint val,    bool add);
  event Fact(uint entity, string attribute, address val, bool add);
  event Fact(uint entity, string attribute, bytes val,   bool add);
  event Fact(uint entity, string attribute, bytes32 val, bool add);


  /************/
  /* Transact */
  /************/

  function transact(uint entity, string attribute, string val) public {
    emit Fact(entity, attribute, val, true);
  }
  function transact(uint entity, string attribute, uint val) public {
    emit Fact(entity, attribute, val, true);
  }
  function transact(uint entity, string attribute, address val) public {
    emit Fact(entity, attribute, val, true);
  }

  function transact(uint entity, string attribute, bytes val) public {
    emit Fact(entity, attribute, val, true);
  }

  function transact(uint entity, string attribute, bytes32 val) public {
    emit Fact(entity, attribute, val, true);
  }


  /***********/
  /* Removes */
  /***********/

  function remove(uint entity, string attribute, string val) public {
    emit Fact(entity, attribute, val, false);
  }

  function remove(uint entity, string attribute, uint val) public {
    emit Fact(entity, attribute, val, false);
  }
  function remove(uint entity, string attribute, address val) public {
    emit Fact(entity, attribute, val, false);
  }

  function remove(uint entity, string attribute, bytes val) public {
    emit Fact(entity, attribute, val, false);
  }

  function remove(uint entity, string attribute, bytes32 val) public {
    emit Fact(entity, attribute, val, false);
  }

}
